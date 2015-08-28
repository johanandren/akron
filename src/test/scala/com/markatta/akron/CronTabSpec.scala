package com.markatta.akron

import akka.actor.{ActorRef, ActorSystem, Cancellable, Props}
import akka.testkit._
import org.scalatest.{ShouldMatchers, WordSpecLike}

import scala.concurrent.duration.FiniteDuration

class CronTabSpec
  extends TestKit(ActorSystem("test"))
  with WordSpecLike
  with ShouldMatchers {

  "the simple crontab actor" should {

    "schedule a new job" in {
      val probe = TestProbe()
      val recipient = TestProbe()
      val crontab = system.actorOf(Props(new CronTab {
        // for testability
        override def schedule(offsetFromNow: FiniteDuration, recipient: ActorRef, message: Any): Cancellable = {
          probe.ref ! (offsetFromNow, recipient, message)
          new Cancellable {
            override def isCancelled: Boolean = true
            override def cancel(): Boolean = true
          }
        }
      }))

      crontab ! CronTab.Schedule(recipient.ref, "woo", CronExpression("* * * * *"))

      val (timing, where, what) = probe.expectMsgType[(FiniteDuration, ActorRef, Any)]
      where should equal (crontab)
    }
  }
}
