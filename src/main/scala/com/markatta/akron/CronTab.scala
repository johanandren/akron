/*
 * Copyright 2015 Johan Andrén
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.markatta.akron

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.TimerTask
import java.util.UUID

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.Signal
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.util.Timeout
import com.markatta.akron.TimeUtils.durationBetween

import scala.collection.immutable.Seq
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

/**
 * The simple crontab actor looses its state upon stop, does not remember what jobs
 * has already been run. Only useful in a single actor system.
 */
object CronTab {

  // protocol
  /**
   * Not for user extension
   */
  sealed trait Command

  /** schedule a message to be sent to an actor with a cron expression, the actor
    * will reply with a [[Scheduled]] message containing a UUID for the crontab entry,
    * If that actor terminates, the crontab entry will be removed
    */
  final case class Schedule[T](entryLabel: String, serviceKey: ServiceKey[T], message: T, when: CronExpression, replyTo: ActorRef[Scheduled[T]]) extends Command
  final case class Scheduled[T](id: UUID, entryLabel: String, serviceKey: ServiceKey[T], message: T)

  /** remove a scheduled job from the crontab, actor will reply with [[UnScheduled]] */
  final case class UnSchedule(id: UUID, replyTo: ActorRef[UnScheduled]) extends Command
  final case class UnScheduled(id: UUID)

  /** Get a list of all the current jobs in the crontab, actor will reply with [[ListOfJobs]] */
  case class GetListOfJobs(replyTo: ActorRef[ListOfJobs]) extends Command
  final case class ListOfJobs(jobs: Seq[Job[_]]) {
    def getJobs(): java.util.List[Job[_]] = jobs.asJava
  }

  private[akron] case object CleanupTick extends Command

  /**
   * Models a scheduled job
   * @param id A unique id identifying this job
   */
  final case class Job[T](id: UUID, label: String, serviceKey: ServiceKey[T], message: T, when: CronTrigger)

  private[akron] final case class Trigger(jobs: Seq[(UUID, LocalDateTime)]) extends Command
  private[akron] final case class Entry(job: Job[_], nextExecutionTime: LocalDateTime)
  private[akron] final class TriggerTask(ids: Seq[(UUID, LocalDateTime)], recipient: ActorRef[Trigger]) extends TimerTask {
    override def run(): Unit = {
      recipient ! Trigger(ids)
    }
  }

  private[akron] val keepRecentExecutionsFor = 24.hours
  private[akron] final case class State(
    jobs: Seq[Job[_]],
    upcomingExecutions: Seq[(UUID, LocalDateTime)],
    recentExecutions: Seq[(UUID, LocalDateTime)]
  ) {

    def jobAlreadyExists(serviceKey: ServiceKey[_], message: Any, when: CronExpression): Boolean =
      jobs.exists(job => job.serviceKey == serviceKey && job.message == message && job.when == when)

    def addJob(job: Job[_], firstTriggerTime: LocalDateTime): State =
      copy(jobs = jobs :+ job, upcomingExecutions = upcomingExecutions :+ (job.id, firstTriggerTime))

    def removeJob(id: UUID): State =
      copy(
        jobs.filterNot(_.id == id),
        upcomingExecutions.filterNot(_._1 == id),
        recentExecutions.filterNot(_._1 == id)
      )

    def jobExecuted(id: UUID, when: LocalDateTime): State = {
      val newUpcoming = upcomingExecutions.find(_ == (id, when)) match {
        case Some(upcoming) =>
          // calculate next from the minute after this one
          val maybeNext = jobs.find(_.id == id).get.when.nextTriggerTime(when.plusMinutes(1))
          upcomingExecutions.filterNot(_ eq upcoming) ++ (maybeNext match {
            case Some(next) => Seq((id, next))
            case None => Seq.empty
          })
        case None =>
          throw new IllegalStateException("jobExecuted called with a job that was never in the list of upcoming jobs")
      }
      copy(upcomingExecutions = newUpcoming, recentExecutions = recentExecutions :+ ((id, when)))
    }

    def nextUp(): Seq[(UUID, LocalDateTime)] = {
      import TimeUtils.ascTimeOrdering
      val sortedEntries = upcomingExecutions.sortBy(_._2)

      sortedEntries.headOption match {
        case Some(earliest) =>
          // one or more jobs next up
          sortedEntries.takeWhile(_._2 == earliest._2)
        case None => Nil
      }
    }

    def cleanupRecentExecutions(): State = {
      val deadline = LocalDateTime.now().minus(keepRecentExecutionsFor.toMinutes, ChronoUnit.MINUTES)
      copy(recentExecutions = recentExecutions.filter { case (_, timestamp) => timestamp.isAfter(deadline) })
    }

  }
  private[akron] def emptyState = State(
    jobs = Seq.empty,
    upcomingExecutions = Seq.empty,
    recentExecutions = Seq.empty
  )

  private[akron] def runJob(context: ActorContext[Command], state: State, id: UUID): Unit = {
    state.jobs.find(_.id == id) match {
      case Some(job) =>
        context.log.debugN(
          "Requesting services for [{}] to run {} ({})",
          job.serviceKey,
          job.label,
          id
        )
        implicit val timeout: Timeout = 5.seconds
        import akka.actor.typed.scaladsl.AskPattern._
        implicit val scheduler = context.system.scheduler
        implicit val ec = context.executionContext
        val logger = context.log
        context.system.receptionist.ask((replyTo: ActorRef[Receptionist.Listing]) =>
          Receptionist.Find(job.serviceKey, replyTo)
        ).onComplete {
          case Success(listing :Receptionist.Listing) =>
            val refs = listing.allServiceInstances(job.serviceKey)
            if (refs.isEmpty)
              logger.warnN(
                "No available services for {} when running job {} ({})",
                job.serviceKey,
                job.label,
                id)
            else {
              // we know the type is right though becasue of schedule type bound
              logger.debugN(
                "Sending message for {} ({}) to service after lookup, registered services [{}]",
                job.label,
                job.id,
                refs.mkString(",")
              )
              refs.foreach(_.asInstanceOf[ActorRef[Any]] ! job.message)
            }
          case Failure(_) =>
            logger.warnN(
              "Timeout waiting for available services for {} when running job {} ({})",
              job.serviceKey,
              job.label,
              id)
        }

      case None =>
        // job was cancelled before we got to run it?
    }
  }

  private[akron] def scheduleNext(context: ActorContext[Command], timer: java.util.Timer, state: State, currentNextTrigger: Option[TriggerTask]): Option[TriggerTask] = {
    // one or more jobs next up
    val nextUp = state.nextUp()
    currentNextTrigger.foreach(_.cancel())
    if (nextUp.nonEmpty) {
      val nextTime = nextUp.head._2
      val now = LocalDateTime.now()

      if (nextTime.isBefore(now) || nextTime.isEqual(now)) {
        // oups, we missed it, trigger right away
        context.log.warnN("Missed execution of [{}] at {}, triggering right away", nextUp.mkString(", "), nextTime)
        context.self ! Trigger(nextUp)
        None
      } else {
        val offsetFromNow = durationBetween(now, nextTime)
        context.log.debugN("Next jobs up {} will run at {}, which is in {}s", nextUp, nextTime, offsetFromNow.toSeconds)
        val task = new TriggerTask(nextUp, context.self)
        timer.schedule(task, offsetFromNow.toMillis)
        Some(task)
      }
    } else None
  }


  // public factories

  def create(): Behavior[Command] = apply()

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    context.log.info("Akron non-persistent crontab starting up")

    new CronTab(context)
  }




}


private[akron] class CronTab(context: ActorContext[CronTab.Command]) extends AbstractBehavior[CronTab.Command] {

  import CronTab._
  import TimeUtils._

  private var nextTrigger: Option[TriggerTask] = None
  private val timer = new java.util.Timer(context.self.path.toString)
  private var state = CronTab.emptyState

  override def onMessage(cmd: Command): Behavior[Command] = cmd match {
    case Schedule(label, serviceKey, message, when, _) if state.jobAlreadyExists(serviceKey, message, when) =>
      context.log.warnN(
        "Not scheduling job {} to send {} to {} since an identical job already exists",
        label,
        message,
        serviceKey)
      this

    case Schedule(label, serviceKey, message, when, replyTo) =>
      val id = UUID.randomUUID()
      val job = Job(id, label, serviceKey, message, when)
      job.when.nextTriggerTime(LocalDateTime.now()) match {
        case Some(firstTriggerTime) =>
          context.log.infoN(
            "Scheduling {} [{}] to send {} to {}, cron expression: [{}], first execution {}",
            label,
            id,
            message,
            serviceKey,
            when,
            firstTriggerTime
          )
          state = state.addJob(job, firstTriggerTime)
        case None =>
          context.log.warn("Not scheduling {} [{}] to send {} to {}, since cron expression: [{}] means there is no future execution time")
      }
      nextTrigger = scheduleNext(context, timer, state, nextTrigger)
      replyTo ! Scheduled(id, label, serviceKey, message)
      this

    case Trigger(jobs) =>
      context.log.info(s"Triggering scheduled job(s) [{}]", jobs.mkString(", "))
      val recent = state.recentExecutions.toSet
      jobs.foreach { job =>
        // make sure it wasn't already run
        if (!recent(job)) {
          val (uuid, when) = job
          state = state.jobExecuted(uuid, when)
          runJob(context, state, uuid)
        }
      }
      nextTrigger = scheduleNext(context, timer, state, nextTrigger)
      this

    case UnSchedule(id, replyTo) =>
      context.log.info("Unscheduling job [{}]", id)
      state = state.removeJob(id)
      nextTrigger = scheduleNext(context, timer, state, nextTrigger)
      replyTo ! UnScheduled(id)
      this

    case GetListOfJobs(replyTo) =>
      replyTo ! ListOfJobs(state.jobs)
      this

  }

  override def onSignal: PartialFunction[Signal, Behavior[Command]] = {
    case PostStop =>
      timer.cancel()
      this
  }

}
