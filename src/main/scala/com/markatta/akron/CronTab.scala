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
import java.util.UUID

import akka.actor._

import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration

/**
 * The simple crontab actor looses its state upon stop, does not remember what jobs
 * has already been run. Only useful in a single actor system.
 */
object CronTab {

  // protocol
  /** schedule a message to be sent to an actor with a cron expression, the actor
    * will reply with a [[Scheduled]] message containing a UUID for the crontab entry,
    * If that actor terminates, the crontab entry will be removed
    */
  case class Schedule(recipient: ActorRef, message: Any, when: CronExpression)
  case class Scheduled(id: UUID, recipient: ActorRef, message: Any)

  /** remove a scheduled job from the crontab, actor will reply with [[UnScheduled]] */
  case class UnSchedule(id: UUID)
  case class UnScheduled(id: UUID)

  /** Get a list of all the current jobs in the crontab, actor will reply with [[ListOfJobs]] */
  case object GetListOfJobs
  case class ListOfJobs(jobs: Seq[Job])



  /** models a scheduled job */
  case class Job(id: UUID, recipient: ActorRef, message: Any, when: CronExpression)


  def props = Props(classOf[CronTab])
}



class CronTab extends Actor with ActorLogging {

  import CronTab._
  import TimeUtils._
  import context.dispatcher

  case class Trigger(id: UUID)

  case class Entry(job: Job, nextExecutionTime: LocalDateTime)

  var entries: Vector[Entry] = Vector()
  var lastScheduled: Option[Cancellable] = None
  var nextScheduled: Option[(UUID, LocalDateTime)] = None

  override def receive: Receive = {

    case Schedule(recipient, message, when) if jobAlreadyExists(recipient, message, when) =>
      log.warning("Not scheduling job to send {} to {} since an identical job already exists", message, recipient)

    case Schedule(recipient, message, when) =>
      val id = UUID.randomUUID()
      log.info("Scheduling {} to send {} to {}, cron expression: {}", id, message, recipient.path, when)
      context.watch(recipient)
      addJob(Job(id, recipient, message, when))
      updateNext()
      sender() ! Scheduled(id, recipient, message)


    case Trigger(id) =>
      runJob(id)
      updateNext()

    case Terminated(actor) =>
      entries.filter(_.job.recipient == actor)
        .foreach { entry =>
          log.info("Removing entry {} because destination actor terminated", entry.job.id)
          removeJob(entry.job.id)
        }
      updateNext()

    case UnSchedule(id) =>
      log.info("Unscheduling job {}", id)
      removeJob(id)
      updateNext()

    case GetListOfJobs =>
      sender() ! ListOfJobs(entries.map(_.job))

  }

  def runJob(id: UUID): Unit = {
    entries = entries.map {
      case Entry(job @ Job(`id`, recipient, message, expr), _) =>
        log.debug("Running job {} sending '{}' to [{}]", id, message, recipient.path)
        recipient ! message
        // offset the time one minute, so that we do not end up running this instance again
        Entry(job, job.when.nextOccurrence(moveIntoNextMinute(LocalDateTime.now())))

      case x => x
    }
  }

  def removeJob(id: UUID): Unit ={
    entries.find(_.job.id == id).foreach { entry =>
      entries = entries.filterNot(_ == entry)

      // make sure we don't monitor lifecycle of actors we don't have
      // jobs for
      if (!entries.exists(_.job.recipient == entry.job.recipient)) {
        context.unwatch(entry.job.recipient)
      }
    }
  }

  def addJob(job: Job): Unit = {
    entries = entries :+ Entry(job, job.when.nextOccurrence)
  }

  def updateNext(): Unit = {
    entries = entries.sortBy(_.nextExecutionTime)
    val nextUp = entries.headOption
    val isAlreadyScheduled = 
      nextUp.map(e => (e.job.id, e.nextExecutionTime)) == nextScheduled

    if (!isAlreadyScheduled) {
      lastScheduled.foreach(_.cancel())
      lastScheduled = nextUp.fold[Option[Cancellable]](
        None
      ) { case Entry(Job(id, _, _, _), nextTime) =>
        val offsetFromNow = durationUntil(nextTime)
        log.debug("Next job up {} will run at {}, which is in {}", id, nextTime, offsetFromNow)
        Some(schedule(offsetFromNow, self, Trigger(id)))
      }
      nextScheduled = nextUp.map(entry => (entry.job.id, entry.nextExecutionTime))
    }
  }

  // for testability
  def schedule(offsetFromNow: FiniteDuration, recipient: ActorRef, message: Any): Cancellable =
    context.system.scheduler.scheduleOnce(offsetFromNow, recipient, message)


  def jobAlreadyExists(recipient: ActorRef, message: Any, when: CronExpression): Boolean =
    entries.exists(e => e.job.recipient == recipient && e.job.message == message && e.job.when == when)

  override def postStop(): Unit = {
    lastScheduled.foreach(_.cancel())
  }
}
