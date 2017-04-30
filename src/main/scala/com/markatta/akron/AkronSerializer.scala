/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.typesafe.com>
 */
package com.markatta.akron

import java.time.{LocalDateTime, ZoneOffset}
import java.util.UUID

import akka.actor.{ActorPath, ActorRef, ExtendedActorSystem}
import akka.serialization.{BaseSerializer, SerializationExtension, SerializerWithStringManifest}
import com.google.protobuf.ByteString
import com.markatta.akron.Akron.RecentExecution
import com.markatta.akron.CronTab.Job

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.collection.JavaConverters._

/**
 * INTERNAL API
 */
final class AkronSerializer(override val system: ExtendedActorSystem) extends SerializerWithStringManifest with BaseSerializer {

  private lazy val serialization = SerializationExtension(system)

  private val JobScheduledManifest = "JS"
  private val JobRemovedManifest = "JR"
  private val ScheduleSnapshotManifest = "SS"

  private val ScheduleManifest = "CS"
  private val ScheduledManifest = "CC"
  private val UnscheduleManifest = "CU"
  private val UnscheduledManifest = "CN"
  private val GetListOfJobsManifest = "GJ"
  private val ListOfJobsManifest = "LJ"

  override def manifest(o: AnyRef): String = o match {
    // persistent events
    case _: PersistentCrontab.JobScheduled => JobScheduledManifest
    case _: PersistentCrontab.JobRemoved => JobRemovedManifest
    case _: PersistentCrontab.ScheduleSnapshot => ScheduleSnapshotManifest
    // crontab protocol
    case _: CronTab.Schedule => ScheduleManifest
    case _: CronTab.Scheduled => ScheduledManifest
    case _: CronTab.UnSchedule => UnscheduleManifest
    case _: CronTab.UnScheduled => UnscheduledManifest
    case CronTab.GetListOfJobs => GetListOfJobsManifest
    case _: CronTab.ListOfJobs => ListOfJobsManifest

    case s => throw new UnsupportedOperationException(s"not implemented for $s")
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    // persistent events
    case js: PersistentCrontab.JobScheduled => jobScheduledToArray(js)
    case jr: PersistentCrontab.JobRemoved => jobRemovedToArray(jr)
    case s: PersistentCrontab.ScheduleSnapshot => snapshotToArray(s)
    // crontab protocol
    case sc: CronTab.Schedule => scheduleToArray(sc)
    case sc: CronTab.Scheduled => scheduledToArray(sc)
    case us: CronTab.UnSchedule => unscheduleToArray(us)
    case us: CronTab.UnScheduled => unscheduledToArray(us)
    case CronTab.GetListOfJobs => Array.emptyByteArray
    case lj: CronTab.ListOfJobs => listOfJobsToArray(lj)

    case s => throw new UnsupportedOperationException(s"not implemented for $s")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    // persistent events
    case JobScheduledManifest => jobScheduledFromBytes(bytes)
    case JobRemovedManifest => jobRemovedFromBytes(bytes)
    case ScheduleSnapshotManifest => snapshotFromBytes(bytes)
    // crontab protocol
    case ScheduleManifest => scheduleFromBytes(bytes)
    case ScheduledManifest => scheduledFromBytes(bytes)
    case UnscheduleManifest => unscheduleFromBytes(bytes)
    case UnscheduledManifest => unscheduledFromBytes(bytes)
    case GetListOfJobsManifest => CronTab.GetListOfJobs
    case ListOfJobsManifest => listOfJobsFromBytes(bytes)

    case s => throw new UnsupportedOperationException(s"not implemented for $s")
  }


  // top level serializers

  private def jobScheduledToArray(js: PersistentCrontab.JobScheduled): Array[Byte] = {
    val b = Akron.JobScheduled.newBuilder()
    b.setJob(jobToBuilder(js.job))
    b.setTimestamp(timestampToLong(js.timestamp))
    if (js.who != system.deadLetters.path.toString)
      b.setWho(js.who)
    b.build().toByteArray
  }

  private def jobRemovedToArray(jr: PersistentCrontab.JobRemoved): Array[Byte] = {
    val b = Akron.JobRemoved.newBuilder()
    b.setId(jr.id.toString)
    b.setTimestamp(timestampToLong(jr.timestamp))
    if (jr.who != system.deadLetters.path.toString)
      b.setWho(jr.who)
    b.build().toByteArray
  }

  private def snapshotToArray(s: PersistentCrontab.ScheduleSnapshot): Array[Byte] = {
    val b = Akron.ScheduleSnapshot.newBuilder()
    s.jobs.foreach { job =>
      b.addJobs(jobToBuilder(job))
    }
    s.recentExecutions.foreach { t =>
      b.addRecentExecutions(tupleToRecentExecution(t))
    }
    b.build().toByteArray
  }

  private def scheduleToArray(sc: CronTab.Schedule): Array[Byte] = {
    val b = Akron.Schedule.newBuilder()
    b.setRecipient(actorRefToString(sc.recipient))
    b.setMessage(anyToEnvelope(sc.message))
    b.setWhen(sc.when.toString)
    b.build().toByteArray
  }

  private def scheduledToArray(sc: CronTab.Scheduled): Array[Byte] = {
    val b = Akron.Sheduled.newBuilder()
    b.setId(sc.id.toString)
    b.setMessage(anyToEnvelope(sc.message))
    b.setRecipient(actorRefToString(sc.recipient))
    b.build().toByteArray
  }

  private def unscheduleToArray(us: CronTab.UnSchedule): Array[Byte] = {
    val b = Akron.UnSchedule.newBuilder()
    b.setId(us.id.toString)
    b.build().toByteArray
  }

  private def unscheduledToArray(us: CronTab.UnScheduled): Array[Byte] = {
    val b = Akron.UnScheduled.newBuilder()
    b.setId(us.id.toString)
    b.build().toByteArray
  }

  private def listOfJobsToArray(lj: CronTab.ListOfJobs): Array[Byte] = {
    val b = Akron.ListOfJobs.newBuilder()
    lj.jobs.foreach(job =>
      b.addJobs(jobToBuilder(job))
    )
    b.build().toByteArray
  }

  // nested serialized objects


  private def jobToBuilder(job: Job): Akron.Job = {
    val b = Akron.Job.newBuilder()
    b.setId(job.id.toString)
    b.setMessage(anyToEnvelope(job.message))
    b.setCronExpression(job.when.toString)
    b.setRecipient(actorRefToString(job.recipient))
    b.build()
  }

  private def anyToEnvelope(any: Any): Akron.Envelope = {
    val ref = any.asInstanceOf[AnyRef]
    val b = Akron.Envelope.newBuilder()
    val serializer = serialization.findSerializerFor(ref)
    b.setSerializerId(serializer.identifier)
    serializer match {
      case ser: SerializerWithStringManifest =>
        b.setManifest(ser.manifest(ref))

      case ser =>
        b.setManifest(
          if (ser.includeManifest) ref.getClass.toString
          else ""
        )
    }
    b.setPayload(ByteString.copyFrom(serializer.toBinary(ref)))
    b.build()
  }

  private def actorRefToString(ref: ActorRef): String =
    ref.path.toSerializationFormat

  private def timestampToLong(timestamp: LocalDateTime) =
    timestamp.toEpochSecond(ZoneOffset.UTC)

  private def tupleToRecentExecution(t: (UUID, LocalDateTime)): Akron.RecentExecution =
    Akron.RecentExecution.newBuilder()
      .setId(t._1.toString)
      .setTimestamp(timestampToLong(t._2))
      .build()


  // deserializers
  private def jobScheduledFromBytes(bytes: Array[Byte]): PersistentCrontab.JobScheduled = {
    val js = Akron.JobScheduled.parseFrom(bytes)
    PersistentCrontab.JobScheduled(
      job = jobFromProto(js.getJob),
      timestamp = timestampFromLong(js.getTimestamp),
      who = js.getWho
    )
  }

  private def jobRemovedFromBytes(bytes: Array[Byte]): PersistentCrontab.JobRemoved = {
    val jr = Akron.JobRemoved.parseFrom(bytes)
    PersistentCrontab.JobRemoved(
      id = UUID.fromString(jr.getId),
      timestamp = timestampFromLong(jr.getTimestamp),
      who = jr.getWho
    )
  }

  private def snapshotFromBytes(bytes: Array[Byte]): PersistentCrontab.ScheduleSnapshot = {
    val ss = Akron.ScheduleSnapshot.parseFrom(bytes)
    PersistentCrontab.ScheduleSnapshot(
      ss.getJobsList.asScala.map(jobFromProto).toVector,
      ss.getRecentExecutionsList.asScala.map(tupleFromRecentExecution).toVector
    )
  }


  private def scheduleFromBytes(bytes: Array[Byte]): CronTab.Schedule = {
    val s = Akron.Schedule.parseFrom(bytes)
    CronTab.Schedule(
      recipient = actorRefFromString(s.getRecipient),
      message = anyFromEnvelope(s.getMessage),
      when = CronExpression(s.getWhen)
    )
  }

  private def scheduledFromBytes(bytes: Array[Byte]): CronTab.Scheduled = {
    val s = Akron.Sheduled.parseFrom(bytes)
    CronTab.Scheduled(
      id = UUID.fromString(s.getId),
      recipient = actorRefFromString(s.getRecipient),
      message = anyFromEnvelope(s.getMessage)
    )
  }

  private def unscheduleFromBytes(bytes: Array[Byte]): CronTab.UnSchedule= {
    val u = Akron.UnSchedule.parseFrom(bytes)
    CronTab.UnSchedule(UUID.fromString(u.getId))
  }

  private def unscheduledFromBytes(bytes: Array[Byte]): CronTab.UnScheduled = {
    val u = Akron.UnScheduled.parseFrom(bytes)
    CronTab.UnScheduled(UUID.fromString(u.getId))
  }

  private def listOfJobsFromBytes(bytes: Array[Byte]): CronTab.ListOfJobs = {
    val lj = Akron.ListOfJobs.parseFrom(bytes)
    CronTab.ListOfJobs(
      lj.getJobsList.asScala.map(jobFromProto).toVector
    )
  }

  // nested deserializers

  private def timestampFromLong(timestamp: Long) =
    LocalDateTime.ofEpochSecond(timestamp, 0, ZoneOffset.UTC)

  private def jobFromProto(job: Akron.Job): CronTab.Job = {
    CronTab.Job(
      id = UUID.fromString(job.getId),
      recipient = actorRefFromString(job.getRecipient),
      message = anyFromEnvelope(job.getMessage),
      when = CronExpression(job.getCronExpression)
    )
  }

  private def anyFromEnvelope(env: Akron.Envelope): Any = {
    serialization.deserialize(
      env.getPayload.toByteArray,
      env.getSerializerId,
      env.getManifest).get
  }

  private def actorRefFromString(ref: String): ActorRef =
    // TODO this blocking resolution is no good, we should keep path or selection as recipient instead
    Await.result(
      system.actorSelection(ActorPath.fromString(ref)).resolveOne(5.seconds),
      5.seconds)

  private def tupleFromRecentExecution(re: RecentExecution): (UUID, LocalDateTime) =
    (UUID.fromString(re.getId), timestampFromLong(re.getTimestamp))
}
