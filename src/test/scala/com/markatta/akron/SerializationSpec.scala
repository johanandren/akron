/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.typesafe.com>
 */
package com.markatta.akron

import java.time.LocalDateTime
import java.util.UUID

import akka.actor.{Actor, ActorSystem, Props}
import akka.serialization.{SerializationExtension, SerializerWithStringManifest}
import akka.testkit.TestKit
import com.markatta.akron
import org.scalatest.{Matchers, WordSpec, WordSpecLike}

import scala.util.Try

object SerializationSpec {
  class DummyActor extends Actor {
    def receive = Actor.emptyBehavior
  }
}

class SerializationSpec extends TestKit(ActorSystem("SerializationSpec")) with WordSpecLike with Matchers {

  val dummySender = system.actorOf(Props(new akron.SerializationSpec.DummyActor))

  // serialization looses nanosecond part, so use this with zero nanos for comparison
  val timestamp = LocalDateTime.now().withNano(0)

  "The Akron serialization" should {
    "serialize and deserialize" in {
      val serialization = SerializationExtension(system)

      Vector(
        PersistentCrontab.JobScheduled(
          CronTab.Job(UUID.randomUUID(), dummySender, "message", CronExpression("* * * * *")),
          timestamp,
          "/user/who-did-it"),
        PersistentCrontab.JobRemoved(
          UUID.randomUUID(),
          timestamp,
          "/user/remover"
        ),
        PersistentCrontab.ScheduleSnapshot(
          Vector(
            CronTab.Job(UUID.randomUUID(), dummySender, "message", CronExpression("* 10 * * *")),
            CronTab.Job(UUID.randomUUID(), dummySender, "message", CronExpression("20 * * * *"))
          )
        ),
        CronTab.GetListOfJobs,
        CronTab.ListOfJobs(Vector(
          CronTab.Job(UUID.randomUUID(), dummySender, "message", CronExpression("* 10 * * *")),
          CronTab.Job(UUID.randomUUID(), dummySender, "message", CronExpression("20 * * * *"))
        )),
        CronTab.Schedule(
          dummySender,
          "schedule-message",
          CronExpression("* * 5 * *")
        ),
        CronTab.Scheduled(
          UUID.randomUUID(),
          dummySender,
          "schedule-message"
        ),
        CronTab.UnSchedule(
          UUID.randomUUID()
        ),
        CronTab.UnScheduled(
          UUID.randomUUID()
        )
      ).foreach { obj =>
        val serializer = serialization.findSerializerFor(obj)
        serializer shouldBe an[AkronSerializer]

        val manifest = serializer match {
          case ser: SerializerWithStringManifest => ser.manifest(obj)
          case ser =>
            if (ser.includeManifest) obj.getClass.toString
            else ""
        }
        val bytes = serializer.toBinary(obj)
        val deserialized = serialization.deserialize(bytes, serializer.identifier, manifest).get

        deserialized should equal(obj)

      }
    }

  }
}
