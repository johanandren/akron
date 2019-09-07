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
import java.util.TimerTask
import java.util.UUID

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.Signal
import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors

import scala.collection.immutable.Seq
import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration
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
  final case class Schedule[T](recipient: ActorRef[T], message: T, when: CronExpression, replyTo: ActorRef[Scheduled[T]]) extends Command
  final case class Scheduled[T](id: UUID, recipient: ActorRef[T], message: T)

  /** remove a scheduled job from the crontab, actor will reply with [[UnScheduled]] */
  final case class UnSchedule(id: UUID, replyTo: ActorRef[UnScheduled]) extends Command
  final case class UnScheduled(id: UUID)

  /** Get a list of all the current jobs in the crontab, actor will reply with [[ListOfJobs]] */
  case class GetListOfJobs(replyTo: ActorRef[ListOfJobs]) extends Command
  final case class ListOfJobs(jobs: Seq[Job[_]]) {
    def getJobs(): java.util.List[Job[_]] = jobs.asJava
  }

  /**
   * Models a scheduled job
   * @param id A unique id identifying this job
   */
  final case class Job[T](id: UUID, recipient: ActorRef[T], message: T, when: CronTrigger)

  private[akron] final case class Trigger(ids: Seq[(UUID, LocalDateTime)]) extends Command
  private[akron] final case class Entry(job: Job[_], nextExecutionTime: LocalDateTime)
  private[akron] final class TriggerTask[T](msg: T, recipient: ActorRef[T]) extends TimerTask {
    override def run(): Unit = {
      recipient ! msg
    }
  }


  def create(): Behavior[Command] = apply()

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    context.log.debug("Akron non-persistent crontab starting up")

    new CronTab(context)
  }

}


private[akron] class CronTab(context: ActorContext[CronTab.Command]) extends AbstractBehavior[CronTab.Command] {

  import CronTab._
  import TimeUtils._

  override def onMessage(cmd: Command): Behavior[Command] = cmd match {
    case Schedule(recipient, message, when, _) if jobAlreadyExists(recipient, message, when) =>
      context.log.warn("Not scheduling job to send {} to {} since an identical job already exists", message, recipient)
      this

    case Schedule(recipient, message, when, replyTo) =>
      val id = UUID.randomUUID()
      context.log.info("Scheduling {} to send {} to {}, cron expression: {}", id, message, recipient.path, when)
      context.watch(recipient)
      addJob(Job(id, recipient, message, when))
      updateNext()
      replyTo ! Scheduled(id, recipient, message)
      this

    case Trigger(ids) =>
      context.log.info(s"Triggering scheduled job(s) $ids")
      ids.foreach { case (uuid, _) => runJob(uuid) }
      updateNext()
      this

    case UnSchedule(id, replyTo) =>
      context.log.info("Unscheduling job {}", id)
      removeJob(id)
      updateNext()
      replyTo ! UnScheduled(id)
      this

    case GetListOfJobs(replyTo) =>
      replyTo ! ListOfJobs(entries.map(_.job))
      this

  }

  override def onSignal: PartialFunction[Signal, Behavior[Command]] = {
    case Terminated(actor) =>
      entries.filter(_.job.recipient == actor)
        .foreach { entry =>
          context.log.info("Removing entry {} because destination actor terminated", entry.job.id)
          removeJob(entry.job.id)
        }
      updateNext()
      this

    case PostStop =>
      timer.cancel()
      this
  }

  lazy val settings = new AkronSettings(context.system.settings.config)

  private var _entries = Vector[Entry]()
  protected final def entries: Vector[Entry] = _entries

  private var nextTrigger: Option[TriggerTask[_]] = None

  private val timer = new java.util.Timer(context.self.path.toString)

  final def updateNext(): Unit = {
    _entries = _entries.sortBy(_.nextExecutionTime)
    // one or more jobs next up
    val nextUp: Seq[Entry] =
      _entries.headOption.fold(Vector.empty[Entry])(earliest =>
        _entries.takeWhile(_.nextExecutionTime == earliest.nextExecutionTime)
      )

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

  private def addJob(job: Job[_]): Unit =
    job.when.nextTriggerTime(LocalDateTime.now()).fold(
      context.log.warn("Tried to add job {}", job)
    )(when =>
      _entries = _entries :+ Entry(job, when)
    )

  private def runJob(id: UUID): Unit = {
    _entries = _entries.flatMap {
      case Entry(job @ Job(`id`, recipient, message, expr), _) =>
        context.log.debug("Running job {} sending '{}' to [{}]", id, message, recipient.path)
        recipient ! message
        // offset the time one minute, so that we do not end up running this instance again
        val laterThanNow = moveIntoNextMinute(LocalDateTime.now())
        job.when.nextTriggerTime(laterThanNow).map(when => Entry(job, when))

      case itsAKeeper => Some(itsAKeeper)
    }
  }

  private def removeJob(id: UUID): Unit ={
    _entries.find(_.job.id == id).foreach { entry =>
      _entries = _entries.filterNot(_ == entry)

      // make sure we don't monitor lifecycle of actors we don't have
      // jobs for
      if (!_entries.exists(_.job.recipient == entry.job.recipient)) {
        context.unwatch(entry.job.recipient)
      }
    }
  }

  private def jobAlreadyExists(recipient: ActorRef[_], message: Any, when: CronExpression): Boolean =
    entries.exists(e => e.job.recipient == recipient && e.job.message == message && e.job.when == when)

}
