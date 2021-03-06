package net.petitviolet.supervisor

//package supervisor

import java.util.concurrent.{ Executors, ForkJoinPool, TimeUnit }

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.util.Timeout
import com.typesafe.config.Config
import net.petitviolet.supervisor.ExecutorActor._
import net.petitviolet.supervisor.Supervisor._

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.concurrent.{ ExecutionContext, Future }
import scala.language.implicitConversions
import scala.reflect.ClassTag

private[supervisor] sealed trait State
private[supervisor] case object Close extends State
private[supervisor] case object HalfOpen extends State
private[supervisor] case object Open extends State

sealed abstract class ExecuteMessage[T] {
  private[supervisor] val run: () => Future[T]
}

class Execute[T](task: => Future[T]) extends ExecuteMessage[T] {
  private[supervisor] val run = () => task
}

class ExecuteWithFallback[T](task: => Future[T], _fallback: => T) extends ExecuteMessage[T] {
  private[supervisor] val run = () => task
  private[supervisor] def fallback = () => _fallback
}

object Execute {
  def apply[T](run: => Future[T]): Execute[T] = new Execute(run)

  def unapply[T](execute: Execute[T]): Option[() => Future[T]] = Some(execute.run)
}

object ExecuteWithFallback {
  def apply[T](run: => Future[T], fallback: => T): ExecuteWithFallback[T] = new ExecuteWithFallback(run, fallback)

  def unapply[T](execute: ExecuteWithFallback[T]): Option[(() => Future[T], () => T)] = Some(execute.run, execute.fallback)
}

object Supervisor {
  case object MessageOnOpenException extends RuntimeException("message on `Open`")

  def props[T](config: Config): Props =
    Props(classOf[Supervisor[T]], maxFailCount(config), runTimeout(config), resetWait(config))

  def props[T](maxFailCount: Int, runTimeout: FiniteDuration, resetWait: FiniteDuration): Props =
    Props(classOf[Supervisor[T]], maxFailCount, runTimeout, resetWait)

  private def maxFailCount(config: Config): Int = config.getInt("max-fail-count")
  private def runTimeout(config: Config): FiniteDuration = Duration(config.getLong("run-timeout"), TimeUnit.MILLISECONDS)
  private def resetWait(config: Config): FiniteDuration = Duration(config.getLong("reset-wait"), TimeUnit.MILLISECONDS)

  private[supervisor] case object BecomeHalfOpen

  implicit class SupervisorActor(val actorRef: ActorRef) extends AnyVal {
    import akka.pattern.ask

    import scala.concurrent.duration._
    implicit def timeout: Timeout = Timeout(21474835.seconds) // maximum timeout for default
    private implicit def toFunction[T](any: T): () => T = () => any

    def supervise[T](future: => Future[T], fallback: => T)(implicit ec: ExecutionContext, classTag: ClassTag[T]): Future[T] = {
      (actorRef ? ExecuteWithFallback(future, fallback)).mapTo[T]
    }

    def supervise[T](future: => Future[T])(implicit ec: ExecutionContext, classTag: ClassTag[T]): Future[T] = {
      (actorRef ? Execute(future)).mapTo[T]
    }

    def supervise[T](future: => Future[T], sender: ActorRef)(implicit ec: ExecutionContext, classTag: ClassTag[T]): Future[T] = {
      actorRef.ask(Execute(future))(timeout, sender).mapTo[T]
    }
  }
}

final class Supervisor[T] private (maxFailCount: Int,
                                   runTimeout: FiniteDuration,
                                   resetWait: FiniteDuration) extends Actor with ActorLogging {
  private[supervisor] var failedCount = 0
  private[supervisor] var state: State = Close
  private[supervisor] def threadPool: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())
  private[supervisor] var timerToHalfOpen: Option[Cancellable] = None

  override def receive: Receive = sendToChild

  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case _: ActorInitializationException => Stop
    case _: ActorKilledException         => Stop
    case _: DeathPactException           => Stop
    case t: Throwable =>
      onReceiveFailure(t)
      Stop
  }

  private[this] def onReceiveFailure(t: Throwable): Unit = {
    log.debug(s"ReceiveFailure. state: $state, cause: $t")
    this.state match {
      case Close =>
        failedCount += 1
        if (failedCount >= maxFailCount) {
          becomeOpen()
        }
      case Open | HalfOpen =>
        // re-fail on Open or failed messaging on HalfOpen...
        becomeOpen()
    }
  }

  private[this] def internalBecome(_state: State, _receive: Receive) = {
    log.debug(s"state: $state => ${_state}")
    this.failedCount = 0
    this.state = _state
    context.become(_receive)
  }

  private[supervisor] def becomeClose() = internalBecome(Close, sendToChild)

  private[supervisor] def becomeHalfOpen() = internalBecome(HalfOpen, sendToChild)

  private[supervisor] def becomeOpen() = {
    // schedule to become `HalfOpen` state after defined `resetWait`.
    this.timerToHalfOpen.map { _.cancel() }
    this.timerToHalfOpen = Some(context.system.scheduler.scheduleOnce(resetWait, self, BecomeHalfOpen)(threadPool))
    internalBecome(Open, responseException)
  }

  /**
   * send Message to child actor and receive message from the actor, proxy its result to the caller.
   * Only `Close` or `HalfOpen` state.
   */
  private[this] def sendToChild: Receive = {
    case message: ExecuteMessage[T] =>
      // if fail, catch on `supervisorStrategy`
      log.debug(s"state: $state, message: $message")
      buildChildExecutorActor(message) ! Run
    case ChildSuccess(originalSender, result) =>
      log.debug(s"state: $state, result: $result")
      if (this.state == HalfOpen) {
        becomeClose()
      }
      // response from `ExecuteActor`, proxy to originalSender
      originalSender ! Status.Success(result)
    case ChildFailure(originalSender, t) =>
      onReceiveFailure(t)
      originalSender ! Status.Failure(t)
  }

  private[this] def buildChildExecutorActor(message: ExecuteMessage[T]): ActorRef =
    context actorOf ExecutorActor.props(sender, message, runTimeout)

  /**
   * not send Message to child actor, just return Exception to the caller
   * Only `Open` state.
   */
  private[this] def responseException: Receive = {
    case BecomeHalfOpen if this.state == Open =>
      becomeHalfOpen()
    case ExecuteWithFallback(_, fallback) =>
      sender ! Status.Success(fallback.apply)
    case msg @ Execute(_) =>
      log.debug(s"state: $state, received: $msg")
      sender ! Status.Failure(MessageOnOpenException)
    case ChildSuccess(originalSender, result) =>
      log.debug(s"state: $state, result: $result")
      if (this.state == HalfOpen) becomeClose()
      // response from `ExecuteActor`, proxy to originalSender
      originalSender ! Status.Success(result)
    case ChildFailure(originalSender, t) =>
      onReceiveFailure(t)
      originalSender ! Status.Failure(t)
  }
}
