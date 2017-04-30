package com.markatta.akron

import akka.actor.ActorSystem
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

  implicit val system = ActorSystem(
    "test",
    ConfigFactory.parseString("akka.log-dead-letters=off").withFallback(ConfigFactory.load())
  )

  import akka.actor.ActorDSL._
  val loggingActor = actor(new Act {
    become {
      case x => println("Message: " + x)
    }
  })

  val crontab = system.actorOf(CronTab.props, "crontab")


  println("""Write cron expressions to schedule "[m] [h] [d] [M] [dow] text-msg-to-send" (or 'quit' to quit).""")
  val Pattern = """((?:\S+ ){5})(\S+)""".r
  Stream
    .continually(StdIn.readLine())
    .takeWhile(str => str != "quit")
    .foreach {
      case Pattern(cronexpr, msg) =>
        println(cronexpr.trim)
        CronExpression.parse(cronexpr.trim) match {
          case Success(expr) => crontab ! CronTab.Schedule(loggingActor, msg, expr)
          case Failure(ex) => println("Invalid crontab expression")
        }
      case s => println(s"Invalid expression '$s'")
    }

  Await.result(system.terminate(), Duration.Inf)
}
