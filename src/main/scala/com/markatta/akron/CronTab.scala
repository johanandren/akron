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
import akka.util.Timeout

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

  private[akron] final case class Trigger(ids: Seq[(UUID, LocalDateTime)]) extends Command
  private[akron] final case class Entry(job: Job[_], nextExecutionTime: LocalDateTime)
  private[akron] final class TriggerTask[T](msg: T, recipient: ActorRef[T]) extends TimerTask {
    override def run(): Unit = {
      recipient ! msg
    }
  }

  private[akron] val keepRecentExecutionsFor = 24.hours
  private[akron] final case class State(entries: Seq[Entry], recentExecutions: Seq[(UUID, LocalDateTime)]) {
    def jobAlreadyExists(serviceKey: ServiceKey[_], message: Any, when: CronExpression): Boolean =
      entries.exists(e => e.job.serviceKey == serviceKey && e.job.message == message && e.job.when == when)

    def addJob(job: Job[_]): State =
      job.when.nextTriggerTime(LocalDateTime.now()) match {
        case None =>
          this // will never be executed
        case Some(when) =>
          copy(entries = entries :+ Entry(job, when))
      }

     def removeJob(id: UUID): State = {
      entries.find(_.job.id == id) match {
        case Some(entry) =>
          val newEntries = entries.filterNot(_ == entry)

          // FIXME make sure we don't monitor lifecycle of actors we don't have
          // jobs for
          if (!entries.exists(_.job.serviceKey == entry.job.serviceKey)) {
            // context.unwatch(entry.job.recipient)
          }
          copy(entries = newEntries)

        case None =>
          this
      }
    }

    def jobExecuted(id: UUID, when: LocalDateTime): State = {
      copy(recentExecutions = recentExecutions :+ ((id, when)))
    }

    def nextUp(): Seq[Entry] = {
      val sortedEntries = entries.sortBy(_.nextExecutionTime)

      sortedEntries.headOption match {
        case Some(earliest) =>
          // one or more jobs next up
          sortedEntries.takeWhile(_.nextExecutionTime == earliest.nextExecutionTime)
        case None => Nil
      }
    }

    def cleanupRecentExecutions(): State = {
      val deadline = LocalDateTime.now().minus(keepRecentExecutionsFor.toMinutes, ChronoUnit.MINUTES)
      copy(recentExecutions = recentExecutions.filter { case (_, timestamp) => timestamp.isAfter(deadline) })
    }

  }
  private[akron] def emptyState = State(
    entries = Seq.empty,
    recentExecutions = Seq.empty
  )

  def create(): Behavior[Command] = apply()

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    context.log.debug("Akron non-persistent crontab starting up")

    new CronTab(context)
  }

}


private[akron] class CronTab(context: ActorContext[CronTab.Command]) extends AbstractBehavior[CronTab.Command] {

  import CronTab._
  import TimeUtils._

  private var state = CronTab.emptyState

  override def onMessage(cmd: Command): Behavior[Command] = cmd match {
    case Schedule(label, serviceKey, message, when, _) if state.jobAlreadyExists(serviceKey, message, when) =>
      context.log.warn("Not scheduling job {} to send {} to {} since an identical job already exists", label, message, serviceKey)
      this

    case Schedule(label, serviceKey, message, when, replyTo) =>
      val id = UUID.randomUUID()
      context.log.info("Scheduling {} ({}) to send {} to {}, cron expression: {}", label, id, message, serviceKey, when)
      state = state.addJob(Job(id, label, serviceKey, message, when))
      updateNext()
      replyTo ! Scheduled(id, label, serviceKey, message)
      this

    case Trigger(ids) =>
      context.log.info(s"Triggering scheduled job(s) $ids")
      ids.foreach { case (uuid, _) => runJob(uuid) }
      updateNext()
      this

    case UnSchedule(id, replyTo) =>
      context.log.info("Unscheduling job {}", id)
      state = state.removeJob(id)
      updateNext()
      replyTo ! UnScheduled(id)
      this

    case GetListOfJobs(replyTo) =>
      replyTo ! ListOfJobs(state.entries.map(_.job))
      this

  }

  override def onSignal: PartialFunction[Signal, Behavior[Command]] = {
    case PostStop =>
      timer.cancel()
      this
  }

  private var nextTrigger: Option[TriggerTask[_]] = None
  private val timer = new java.util.Timer(context.self.path.toString)

  final def updateNext(): Unit = {
    // one or more jobs next up
    val nextUp = state.nextUp()
    nextTrigger.foreach(_.cancel())
    if (nextUp.nonEmpty) {
      val nextTime = nextUp.head.nextExecutionTime
      val now = LocalDateTime.now()
      val ids = nextUp.map(entry => (entry.job.id, entry.nextExecutionTime))
      nextTrigger =
        if (nextTime.isBefore(now) || nextTime.isEqual(now)) {
          // oups, we missed it, trigger right away
          context.log.warn("Missed execution of {} at {}, triggering right away", ids, nextTime)
          context.self ! Trigger(ids)
          None
        } else {
          val offsetFromNow = durationBetween(now, nextTime)
          context.log.debug("Next jobs up {} will run at {}, which is in {}s", ids, nextTime, offsetFromNow.toSeconds)
          Some(schedule(offsetFromNow, context.self, Trigger(ids)))
        }
    }
  }


  // for testability
  protected def schedule[T](offsetFromNow: FiniteDuration, recipient: ActorRef[T], message: T): TriggerTask[T] = {
    assert(offsetFromNow.toMillis > 0)
    val task = new TriggerTask[T](message, recipient)
    timer.schedule(task, offsetFromNow.toMillis)
    task
  }

  private def runJob(id: UUID): Unit = {
    state.entries.flatMap {
      case Entry(job @ Job(`id`, label, serviceKey, message, expr), _) =>
        context.log.debug(s"Running job ${label} (${id}) sending '{}' to [{}]", message, serviceKey)
        implicit val timeout: Timeout = 5.seconds
        import akka.actor.typed.scaladsl.AskPattern._
        implicit val scheduler = context.system.scheduler
        implicit val ec = context.system.executionContext
        val logger = context.log
        context.system.receptionist.ask((replyTo: ActorRef[Receptionist.Listing]) =>
          // FIXME message for this instead of onComplete??
          Receptionist.find(serviceKey, replyTo)).onComplete {
          case Success(serviceKey.Listing(refs)) =>
            if (refs.isEmpty) logger.warn("No available services for {} when running job {} ({})", serviceKey, label, id)
            else {
              refs.foreach(_ ! message)
            }
          case Failure(ex) =>
            logger.warn("Timeout waiting for available services for {} when running job {} ({})", serviceKey, label, id)
        }

        // offset the time one minute, so that we do not end up running this instance again
        val laterThanNow = moveIntoNextMinute(LocalDateTime.now())
        job.when.nextTriggerTime(laterThanNow).map(when => Entry(job, when))

      case itsAKeeper => Some(itsAKeeper)
    }
  }

}
