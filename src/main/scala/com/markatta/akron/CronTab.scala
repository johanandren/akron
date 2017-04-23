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

import java.util.UUID

import akka.actor._

import scala.collection.immutable.Seq

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
  final case class Job(id: UUID, recipient: ActorRef, message: Any, when: CronTrigger)


  def props = Props(classOf[CronTab])
}



class CronTab extends Actor with AbstractCronTab with ActorLogging {

  import CronTab._

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
      ids.foreach { case (uuid, _) => runJob(uuid) }
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

}
