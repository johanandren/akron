/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.typesafe.com>
 */
package com.markatta.akron


import java.time.LocalDateTime
import java.util.UUID

import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.RecoveryCompleted
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.RetentionCriteria

import scala.concurrent.duration._

object PersistentCrontab {
  // same protocol as non-persistent crontab

  import CronTab._

  sealed trait Event
  private[akron] case class JobScheduled(job: Job[_], timestamp: LocalDateTime, who:String) extends Event
  private[akron] case class JobRemoved(id: UUID, timestamp: LocalDateTime, who: String) extends Event
  private[akron] case class JobTriggered(id: UUID, timestamp: LocalDateTime) extends Event

  /**
   * Java API:
   */
  def create(): Behavior[CronTab.Command] = apply()

  /**
   * Scala API:
   */
  def apply(): Behavior[CronTab.Command] = apply(PersistenceId("akron-crontab"))

  /**
   * Java API:
   * @param id Persistent id to use for the crontab, only needed if you need multiple crontabs on the same actor system
   */
  def create(id: PersistenceId): Behavior[CronTab.Command] = apply(id)

  /**
   * Scala API:
   * @param id Persistent id to use for the crontab, only needed if you need multiple crontabs on the same actor system
   */
  def apply(id: PersistenceId): Behavior[CronTab.Command] = Behaviors.setup { context =>
    Behaviors.withTimers { timers =>
      context.log.info("Akron persistent crontab starting up")
      timers.startTimerWithFixedDelay(CleanupTick, CleanupTick, 1.hour)
      val timer = new java.util.Timer(context.self.path.toString)

      EventSourcedBehavior[CronTab.Command, Event, State](
        id,
        emptyState,
        { (state, command) =>
          command match {
            case Schedule(label, serviceKey, message, when, _) if state.jobAlreadyExists(serviceKey, message, when) =>
              context.log.warn("Not scheduling job {} to send {} to {} since an identical job already exists", Array(label, message, serviceKey))
              Effect.none
            case Schedule(label, serviceKey, message, when, replyTo) =>
              val id = UUID.randomUUID()
              context.log.info("Scheduling {} ({}) to send {} to {}, cron expression: {}", Array(label, id, message, serviceKey, when))
              val job = Job(id, label, serviceKey, message, when)
              Effect.persist(JobScheduled(job, LocalDateTime.now(), replyTo.path.toString)).thenRun { state =>
                replyTo ! Scheduled(id, label, serviceKey, message)
              }
            case Trigger(ids) =>
              context.log.info(s"Triggering scheduled job(s) $ids")
              Effect.persist(ids.map { case (uuid, stamp) => JobTriggered(uuid, stamp)})
                .thenRun { state =>
                  /*
                  ids.foreach { case (uuid, _) =>
                    // FIXME runJob(uuid)
                  }
                  updateNext()
                  */
                }
            case UnSchedule(id, replyTo) =>
              context.log.info("Unscheduling job {}", id)
              Effect.persist(JobRemoved(id, LocalDateTime.now(), replyTo.path.toString)).thenRun { state =>
                replyTo ! UnScheduled(id)
              }
            case GetListOfJobs(replyTo) =>
              replyTo ! ListOfJobs(state.jobs)
              Effect.none
          }
        },
        { (state, event) =>
          val newState = event match {
            case JobScheduled(job, _, _) =>
              job.when.nextTriggerTime(LocalDateTime.now()) match {
                case Some(nextExecutionTime) =>
                  state.addJob(job, nextExecutionTime)
                case None =>
                  // FIXME we can ignore it, will never be executed again but behaving differently in event handler feels wrong
                  state
              }
            case JobRemoved(id, _, _) =>
              state.removeJob(id)
            case JobTriggered(id, when) =>
              state.jobExecuted(id, when)
          }
          // instead of spamming the journal with a tick event we cleanup every time state is cleaned up
          newState.cleanupRecentExecutions()
        }
      ).receiveSignal {
        case (_, PostStop) =>
          timer.cancel()
        case (_, RecoveryCompleted) =>
          // FIXME schedule next and missed events

      }.withRetention(RetentionCriteria.snapshotEvery(10, 3))
    }
  }
}
/*
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
        addJob(scheduled.job)
        updateNext()
        sender() ! Scheduled(id, recipient, message)
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
        sender() ! UnScheduled(id)
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
*/