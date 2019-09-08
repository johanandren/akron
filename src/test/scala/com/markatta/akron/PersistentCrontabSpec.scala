/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.typesafe.com>
 */
package com.markatta.akron

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.actor.typed.receptionist.ServiceKey
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import org.scalatest.{Matchers, WordSpecLike}


class PersistentCrontabSpec
extends ScalaTestWithActorTestKit(ConfigFactory.parseString(
  """
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
    akka.persistence.journal.leveldb.dir = "target/journal"
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/snapshots"
    akron.persistent-crontab.snapshot-expression = ""
  """))
  with WordSpecLike
  with Matchers
  with LogCapturing {

  def uniqueId() = PersistenceId("test" + System.nanoTime())

  val ServiceKey1 = ServiceKey[String]("service-1")
  val ServiceKey2 = ServiceKey[String]("service-2")

  "the persistent crontab actor" should {

    "keep jobs over restarts" in {
      val probe = createTestProbe[AnyRef]()
      val id = uniqueId()

      val crontab = spawn(PersistentCrontab(id))

      crontab ! CronTab.Schedule("label-1", ServiceKey1, "woo", CronExpression("* * * * *"), probe.ref)
      probe.expectMessageType[CronTab.Scheduled[_]]

      crontab ! CronTab.Schedule("label-2", ServiceKey2, "woo1", CronExpression("2 10 * * *"), probe.ref)
      probe.expectMessageType[CronTab.Scheduled[_]]

      testKit.stop(crontab)

      val revived = spawn(PersistentCrontab(id))

      revived ! CronTab.GetListOfJobs(probe.ref)
      val listOfJobs = probe.expectMessageType[CronTab.ListOfJobs]
      listOfJobs.jobs should have size (2)
    }
  }
}

/*
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.{Matchers, WordSpecLike}

import scala.concurrent.duration.FiniteDuration


class PersistentCrontabSpec
  extends TestKit(
    ActorSystem("PersistentCrontabSpec",
    ConfigFactory.parseString(
    """
      akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
      akka.persistence.journal.leveldb.dir = "target/journal"
      akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      akka.persistence.snapshot-store.local.dir = "target/snapshots"
      akron.persistent-crontab.snapshot-expression = ""
    """))
  ) with ImplicitSender
  with WordSpecLike
  with Matchers {

  def uniqueId() = "test" + System.nanoTime()

    "the persistent crontab actor" should {

      "keep jobs over restarts" in {
        val probe = TestProbe()
        val recipient = TestProbe()
        val id = uniqueId()

        val crontab = system.actorOf(PersistentCrontab.props(id))


        crontab ! CronTab.Schedule(recipient.ref, "woo", CronExpression("* * * * *"))
        expectMsgType[CronTab.Scheduled]

        crontab ! CronTab.Schedule(recipient.ref, "woo1", CronExpression("2 10 * * *"))
        expectMsgType[CronTab.Scheduled]

        system.stop(crontab)

        val revived = system.actorOf(PersistentCrontab.props(id))

        revived ! CronTab.GetListOfJobs
        val listOfJobs = expectMsgType[CronTab.ListOfJobs]
        listOfJobs.jobs should have size(2)
      }

      "schedule a new job" in {

        val id = uniqueId()

        val probe = TestProbe()
        val recipient = TestProbe()
        val crontab = system.actorOf(Props(new PersistentCrontab(id) {
          override def schedule(offsetFromNow: FiniteDuration, recipient: ActorRef, message: Any): TriggerTask = {
            probe.ref ! (offsetFromNow, recipient, message)
            new TriggerTask
          }
        }))

        crontab ! CronTab.Schedule(recipient.ref, "woo", CronExpression("* * * * *"))

        val (timing, where, what) = probe.expectMsgType[(FiniteDuration, ActorRef, Any)]
        where should equal (crontab)
      }

      "not run a job that was run by a previous incarnation" in {
        val id = uniqueId()
        val probe = TestProbe()
        val recipient = TestProbe()
        val props = Props(new PersistentCrontab(id) {
          override def schedule(offsetFromNow: FiniteDuration, recipient: ActorRef, message: Any): TriggerTask = {
            probe.ref ! (offsetFromNow, recipient, message)
            new TriggerTask
          }
        })
        val crontab1 = system.actorOf(props)
        crontab1 ! CronTab.Schedule(recipient.ref, "woo", CronExpression("* * * * *"))
        expectMsgType[CronTab.Scheduled]

        // will always be run because we just missed one
        val (timing, where, what) = probe.expectMsgType[(FiniteDuration, ActorRef, Any)]
        system.stop(crontab1)

        val crontab2 = system.actorOf(props)
        probe.expectNoMsg()
      }
    }
}
*/