package com.markatta.akron

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import com.markatta.akron.CronTab.TriggerTask
import org.scalatest.{Matchers, WordSpecLike}

import scala.concurrent.duration._

class CronTabSpec
  extends ScalaTestWithActorTestKit
  with WordSpecLike
  with Matchers
  with LogCapturing {

  "the simple crontab actor" should {

    "schedule a new job" in {
      val MyKey = ServiceKey[String]("service-id")
      val probe = TestProbe[Any]()
      val recipient = TestProbe[String]()

      system.receptionist ! Receptionist.register(MyKey, recipient.ref, probe.ref)
      probe.expectMessageType[Receptionist.Registered]

      val crontab = spawn(CronTab())

      crontab ! CronTab.Schedule("woo-every-second", MyKey, "woo", CronExpression("* * * * *"), probe.ref)
      probe.expectMessageType[CronTab.Scheduled[_]]

      val (timing, where, what) = probe.receiveMessage()
      where should equal (crontab)
    }
  }
}
