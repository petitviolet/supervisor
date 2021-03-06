package net.petitviolet.ex

import akka.actor.{ ActorLogging, Actor }
import akka.util.Timeout

import scala.concurrent.duration._

object common {

  case class Message(value: String)
  case object PanicMessage
  case object HeavyMessage

  class UnstableActor extends Actor with ActorLogging {
    override def receive: Receive = {
      case Message(value) =>
        log.info(s"UnstableActor: $value")
        sender ! s"receive: $value"
      case PanicMessage =>
        // just fail
        sys.error("--------------------Oops...-------------------")
      case HeavyMessage =>
        // sleep over callTimeout
        Thread.sleep(2000)
        sender ! s"finish $HeavyMessage"
    }
  }

  implicit val timeout = Timeout(5.seconds)
}
