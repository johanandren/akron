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
import java.util.{TimerTask, Timer, UUID}

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
  final case class Schedule(recipient: ActorRef, message: Any, when: CronExpression)
  final case class Scheduled(id: UUID, recipient: ActorRef, message: Any)

  /** remove a scheduled job from the crontab, actor will reply with [[UnScheduled]] */
  final case class UnSchedule(id: UUID)
  final case class UnScheduled(id: UUID)

  /** Get a list of all the current jobs in the crontab, actor will reply with [[ListOfJobs]] */
  case object GetListOfJobs
  final case class ListOfJobs(jobs: Seq[Job])



  /**
   * Models a scheduled job
   * @param id A unique id identifying this job
   */
  @SerialVersionUID(2L)
  case class Job(id: UUID, recipient: ActorRef, message: Any, when: CronTrigger)


  def props = Props(classOf[CronTab])
}



class CronTab extends Actor with ActorLogging {

  import CronTab._
  import TimeUtils._

  case class Trigger(ids: Seq[UUID])

  case class Entry(job: Job, nextExecutionTime: LocalDateTime)

  var entries: Vector[Entry] = Vector()
  var nextTrigger: Option[TriggerTask] = None

  var timer = new Timer(self.path.name)

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


    case Trigger(ids) =>
      log.info(s"Triggering scheduled job(s) $ids")
      ids.foreach(runJob)
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
    entries = entries.flatMap {
      case Entry(job @ Job(`id`, recipient, message, expr), _) =>
        log.debug("Running job {} sending '{}' to [{}]", id, message, recipient.path)
        recipient ! message
        // offset the time one minute, so that we do not end up running this instance again
        val laterThanNow = moveIntoNextMinute(LocalDateTime.now())
        job.when.nextTriggerTime(laterThanNow).map(when => Entry(job, when))

      case itsAKeeper => Some(itsAKeeper)
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

  def addJob(job: Job): Unit =
    job.when.nextTriggerTime(LocalDateTime.now()).fold(
      log.warning("Tried to add job {}", job)
    )(when =>
      entries = entries :+ Entry(job, when)
    )

  def updateNext(): Unit = {
    entries = entries.sortBy(_.nextExecutionTime)
    // one or more jobs next up
    val nextUp: Seq[Entry] =
      entries.headOption.fold(Vector.empty[Entry])(earliest =>
        entries.takeWhile(_.nextExecutionTime == earliest.nextExecutionTime)
      )

    nextTrigger.foreach(_.cancel())
    if (nextUp.nonEmpty) {
      val nextTime = nextUp.head.nextExecutionTime
      val now = LocalDateTime.now()
      val ids = nextUp.map(_.job.id)
      nextTrigger =
        if (nextTime.isBefore(now) || nextTime.isEqual(now)) {
          // oups, we missed it, trigger right away
          log.warning("Missed execution of {} at {}, triggering right away", ids, nextTime)
          self ! Trigger(ids)
          None
        } else {
          val offsetFromNow = durationBetween(now, nextTime)
          log.debug("Next jobs up {} will run at {}, which is in {}s", ids, nextTime, offsetFromNow.toSeconds)
          Some(schedule(offsetFromNow, self, Trigger(ids)))
        }
    }
  }

  // for testability
  def schedule(offsetFromNow: FiniteDuration, recipient: ActorRef, message: Any): TriggerTask = {
    assert(offsetFromNow.toMillis > 0)
    val task = new TriggerTask(message)
    timer.schedule(task, offsetFromNow.toMillis)
    task
  }


  def jobAlreadyExists(recipient: ActorRef, message: Any, when: CronExpression): Boolean =
    entries.exists(e => e.job.recipient == recipient && e.job.message == message && e.job.when == when)

  override def postStop(): Unit = {
    timer.cancel()
  }

  class TriggerTask(msg: Any) extends TimerTask {
    override def run(): Unit = self ! msg
  }
}
