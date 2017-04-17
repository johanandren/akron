/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.typesafe.com>
 */
package com.markatta.akron

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActor, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.{ShouldMatchers, WordSpecLike}

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
  with ShouldMatchers {

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
    }
}
