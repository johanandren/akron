package com.markatta.akron

import akka.actor.{ActorRef, ActorSystem, Cancellable, Props}
import akka.testkit._
import org.scalatest.{Matchers, WordSpecLike}

import scala.concurrent.duration.FiniteDuration

class CronTabSpec
  extends TestKit(ActorSystem("CronTabSpec"))
  with WordSpecLike
  with ImplicitSender
  with Matchers {

  "the simple crontab actor" should {

    "schedule a new job" in {
      val probe = TestProbe()
      val recipient = TestProbe()
      val crontab = system.actorOf(Props(new CronTab {
        // for testability
        override def schedule(offsetFromNow: FiniteDuration, recipient: ActorRef, message: Any): TriggerTask = {
          probe.ref ! (offsetFromNow, recipient, message)
          new TriggerTask
        }
      }))

      crontab ! CronTab.Schedule(recipient.ref, "woo", CronExpression("* * * * *"))
      expectMsgType[CronTab.Scheduled]

      val (timing, where, what) = probe.expectMsgType[(FiniteDuration, ActorRef, Any)]
      where should equal (crontab)
    }
  }
}
