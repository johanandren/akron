/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.typesafe.com>
 */
package com.markatta.akron

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

import akka.actor.{ActorLogging, Props, Terminated}
import akka.persistence.{PersistentActor, RecoveryCompleted}

import scala.collection.immutable.Seq
import scala.concurrent.duration._

object PersistentCrontab {
  // same protocol as non-persistent crontab

  import CronTab.Job
  private[akron] case class JobScheduled(job: Job, timestamp: LocalDateTime, who:String)
  private[akron] case class JobRemoved(id: UUID, timestamp: LocalDateTime, who: String)
  private[akron] case class JobTriggered(id: UUID, timestamp: LocalDateTime)
  private[akron] case class ScheduleSnapshot(jobs: Vector[Job], recentExecutions: Seq[(UUID, LocalDateTime)])

  def props(): Props = props("akron-crontab")

  /**
   * @param id Persistent id to use for the crontab, only needed if you need multiple crontabs on the same actor system
   */
  def props(id: String): Props = Props(new PersistentCrontab(id))
}

class PersistentCrontab(_id: String) extends PersistentActor with ActorLogging with AbstractCronTab {

  import CronTab._
  import PersistentCrontab._
  object MakeSnapshot
  object CleanupTick

  override def persistenceId: String = _id

  def keepRecentExecutionsFor: FiniteDuration = 24.hours

  import context.dispatcher
  context.system.scheduler.schedule(1.hour, 1.hour, self, CleanupTick)

  private var recentExecutions = Seq.empty[(UUID, LocalDateTime)]

  override def receiveRecover: Receive = {
    case JobScheduled(job, _, _) =>
      log.debug("Restoring persisted job {} sending {} to {}, cron expression: {}", job.id, job.message, job.recipient.path, job.when)
      addJob(job)

    case JobRemoved(id, _, _) =>
      log.debug("Persisted remove of id {}", id)
      removeJob(id)

    case JobTriggered(id, timestamp) =>
      recentExecutions = recentExecutions :+ (id -> timestamp)

    case ScheduleSnapshot(jobs, recentExecutionsSnap) =>
      jobs.foreach(addJob)
      recentExecutions = recentExecutionsSnap

    case RecoveryCompleted =>
      updateNext()
      setupSnapshotting()

  }

  override def receiveCommand: Receive = {
    case Schedule(recipient, message, when) if jobAlreadyExists(recipient, message, when) =>
      log.warning("Not scheduling job to send {} to {} since an identical job already exists", message, recipient)

    case Schedule(recipient, message, when) =>
      val id = UUID.randomUUID()
      val job = Job(id, recipient, message, when)
      persist(JobScheduled(job, LocalDateTime.now(), sender().path.toString)) { scheduled =>
        log.info("Scheduling {} to send {} to {}, cron expression: {}", id, message, recipient.path, when)
        context.watch(recipient)
        sender() ! Scheduled(id, recipient, message)
        addJob(scheduled.job)
        updateNext()
      }

    case Trigger(jobs) =>
      log.info(s"Triggering scheduled job(s) $jobs")
      var counter = 0
      val events = jobs.filterNot(recentExecutions.contains)
        .map { case (id, when) => JobTriggered(id, when) }
      persistAll(events) { triggered =>
        counter += 1
        recentExecutions = jobs ++ recentExecutions
        runJob(triggered.id)
        if (counter == jobs.size) {
          updateNext()
          cleanUpRecentExecutions()
        }
      }

    case Terminated(actor) =>
      entries.filter(_.job.recipient == actor)
        .foreach { entry =>
          persist(JobRemoved(entry.job.id, LocalDateTime.now(), sender().path.toString)) { removed =>
            log.info("Removing entry {} because destination actor terminated", entry.job.id)
            removeJob(removed.id)
          }
        }
      updateNext()

    case UnSchedule(id) =>
      persist(JobRemoved(id, LocalDateTime.now(), sender().path.toString)) { removed =>
        log.info("Unscheduling job {}", id)
        removeJob(id)
        updateNext()
      }

    case GetListOfJobs =>
      sender() ! ListOfJobs(jobs)

    case MakeSnapshot =>
      cleanUpRecentExecutions()
      saveSnapshot(ScheduleSnapshot(jobs, recentExecutions))

    case CleanupTick =>
      cleanUpRecentExecutions()
  }

  def jobs = entries.map(_.job)

  def setupSnapshotting(): Unit = {
    val expression = settings.PersistentCrontab.SnapshotExpression
    if (expression.nonEmpty) {
      log.debug("Setting up snapshotting of crontab with cron expression {}", expression)
      addJob(CronTab.Job(UUID.randomUUID(), self, MakeSnapshot,CronExpression(expression)))
    } else {
      log.debug("Snapshotting of crontab disabled")
    }
  }

  def cleanUpRecentExecutions(): Unit = {
    val deadline = LocalDateTime.now().minus(keepRecentExecutionsFor.toMinutes, ChronoUnit.MINUTES)
    recentExecutions = recentExecutions.filter { case (_, timestamp) => timestamp.isAfter(deadline) }
  }

}
