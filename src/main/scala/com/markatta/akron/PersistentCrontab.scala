/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.typesafe.com>
 */
package com.markatta.akron

import java.time.LocalDateTime
import java.util.UUID

import akka.actor.{ActorLogging, Props, Terminated}
import akka.persistence.{PersistentActor, RecoveryCompleted}

object PersistentCrontab {
  // same protocol as non-persistent crontab

  import CronTab.Job
  private[akron] case class JobScheduled(job: Job, timestamp: LocalDateTime, who:String)
  private[akron] case class JobRemoved(id: UUID, timestamp: LocalDateTime, who: String)
  private[akron] case class ScheduleSnapshot(jobs: Vector[Job])

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

  override def persistenceId: String = _id

  override def receiveRecover: Receive = {
    case JobScheduled(job, _, _) =>
      log.debug("Restoring persisted job {} sending {} to {}, cron expression: {}", job.id, job.message, job.recipient.path, job.when)
      addJob(job)

    case JobRemoved(id, _, _) =>
      log.debug("Persisted remove of id {}", id)
      removeJob(id)

    case ScheduleSnapshot(jobs) =>
      jobs.foreach(addJob)

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
        addJob(scheduled.job)
        updateNext()
        sender() ! Scheduled(id, recipient, message)
      }

    case Trigger(ids) =>
      log.info(s"Triggering scheduled job(s) $ids")
      ids.foreach(runJob)
      updateNext()

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
      saveSnapshot(ScheduleSnapshot(jobs))
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

}
