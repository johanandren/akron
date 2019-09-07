package com.markatta.akron

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.testkit.ImplicitSender
import com.markatta.akron.CronTab.TriggerTask
import org.scalatest.{Matchers, WordSpecLike}

import scala.concurrent.duration.FiniteDuration

class CronTabSpec
  extends ScalaTestWithActorTestKit
  with WordSpecLike
  with Matchers {

  "the simple crontab actor" should {

    "schedule a new job" in {
      val probe = TestProbe[Any]()
      val recipient = TestProbe[String]()
      val crontab = spawn(Behaviors.setup[CronTab.Command](context => new CronTab(context) {
        // for testability
        override def schedule[T](offsetFromNow: FiniteDuration, recipient: ActorRef[T], message: T): TriggerTask[T] = {
          probe.ref ! ((offsetFromNow, recipient, message))
          new TriggerTask[T](message, recipient)
        }
      }))

      crontab ! CronTab.Schedule(recipient.ref, "woo", CronExpression("* * * * *"), probe.ref)
      probe.expectMessageType[CronTab.Scheduled[_]]

      val (timing, where, what) = probe.expectMessageType[(FiniteDuration, ActorRef[_], Any)]
      where should equal (crontab)
    }
  }
}
