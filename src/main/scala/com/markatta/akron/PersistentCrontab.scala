/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.typesafe.com>
 */
package com.markatta.akron


import java.time.LocalDateTime
import java.util.UUID

import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.RecoveryCompleted
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.RetentionCriteria
import akka.actor.typed.scaladsl.LoggerOps

import scala.concurrent.duration._

/**
 * The persistent crontab share the protocol with the non-persistent crontab, [[CronTab.Command]]
 */
object PersistentCrontab {

  import CronTab._

  sealed trait Event
  final case class JobScheduled(job: Job[_], timestamp: LocalDateTime, who:String) extends Event
  final case class JobRemoved(id: UUID, timestamp: LocalDateTime, who: String) extends Event
  final case class JobTriggered(id: UUID, timestamp: LocalDateTime) extends Event

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
      val longTermTimer = new java.util.Timer(context.self.path.toString)
      var nextTrigger: Option[TriggerTask] = None

      EventSourcedBehavior[CronTab.Command, Event, State](
        id,
        emptyState,
        { (state, command) =>
          command match {
            case Schedule(label, serviceKey, message, when, _) if state.jobAlreadyExists(serviceKey, message, when) =>
              context.log.warnN("Not scheduling job {} to send {} to {} since an identical job already exists", label, message, serviceKey)
              Effect.none
            case Schedule(label, serviceKey, message, when, replyTo) =>
              val id = UUID.randomUUID()
              context.log.infoN("Scheduling {} ({}) to send {} to {}, cron expression: {}", label, id, message, serviceKey, when)
              val job = Job(id, label, serviceKey, message, when)
              Effect.persist(JobScheduled(job, LocalDateTime.now(), replyTo.path.toString)).thenRun { state =>
                replyTo ! Scheduled(id, label, serviceKey, message)
              }
            case Trigger(ids) =>
              context.log.info("Triggering scheduled job(s) [{}]", ids.mkString(","))
              Effect.persist(ids.map { case (uuid, stamp) => JobTriggered(uuid, stamp)})
                .thenRun { state =>
                  ids.foreach { case (id, _) =>
                    CronTab.runJob(context, state, id)
                  }
                  nextTrigger = scheduleNext(context, longTermTimer, state, nextTrigger)
                }
            case UnSchedule(id, replyTo) =>
              context.log.info("Unscheduling job [{}]", id)
              Effect.persist(JobRemoved(id, LocalDateTime.now(), replyTo.path.toString)).thenRun { state =>
                nextTrigger = scheduleNext(context, longTermTimer, state, nextTrigger)
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
          // instead of spamming the journal with a tick event we cleanup every time state is updated
          newState.cleanupRecentExecutions()
        }
      ).receiveSignal {
        case (_, PostStop) =>
          longTermTimer.cancel()
        case (state, RecoveryCompleted) =>
          // FIXME potentially handle missed events while we didn't run
          nextTrigger = scheduleNext(context, longTermTimer, state, nextTrigger)

      }.withRetention(RetentionCriteria.snapshotEvery(10, 3))
    }
  }
}