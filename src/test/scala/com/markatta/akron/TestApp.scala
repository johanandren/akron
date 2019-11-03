package com.markatta.akron

import java.time.LocalDateTime

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import com.markatta.akron.CronTab.Schedule
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.io.StdIn
import scala.util.{Failure, Success}

/**
 * Test app that can be run using sbt test:run to play around with scheduling message sends
 */
object TestApp extends App {
  trait Command
  case class Message(text: String) extends Command
  case class AddEntry(when: CronExpression, msg: String) extends Command

  val MyServiceKey = ServiceKey[Message]("service")

  val rootBehavior = Behaviors.setup[Command] { context =>
    context.system.receptionist ! Receptionist.Register(MyServiceKey, context.self)

    val crontab = context.spawn(CronTab(), "crontab")

    Behaviors.receiveMessage {
      case AddEntry(when, message) =>
        crontab ! CronTab.Schedule(s"added-${LocalDateTime.now()}", MyServiceKey, Message(message), when, context.system.deadLetters)
        Behaviors.same
      case Message(msg) =>
        println(s"Got message $msg")
        Behaviors.same
    }
  }

  implicit val system = ActorSystem(
    rootBehavior,
    "TestApp",
    ConfigFactory.parseString("akka.log-dead-letters=off").withFallback(ConfigFactory.load())
  )

  system ! AddEntry(CronExpression.parse("* * * * *").get, "every minute")
  system ! AddEntry(CronExpression.parse("*/2 * * * *").get, "every second minute")
  system ! AddEntry(CronExpression.parse("*/5 * * * *").get, "every five minutes")
  system ! AddEntry(CronExpression.parse("0 * * * *").get, "every hour")
}
