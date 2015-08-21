package com.markatta.akron

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import expression.CronExpression
import scala.io.StdIn
import scala.util.{Failure, Success}

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

  val crontab = system.actorOf(SimpleCronTabActor.props, "crontab")


  println("Write cron expressions to schedule ([h] [m] [d] [M] [dow]), quit to quit.")
  Stream
    .continually(StdIn.readLine)
    .takeWhile(str => str != "quit")
    .foreach { line =>
      CronExpression.parse(line) match {
        case Success(expr) => crontab ! SimpleCronTabActor.Schedule(loggingActor, "message", expr)
        case Failure(ex) => println("Invalid crontab expression")
      }
    }

  system.shutdown()
}
