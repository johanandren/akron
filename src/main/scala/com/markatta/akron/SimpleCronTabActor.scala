package com.markatta.akron

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

import akka.actor._
import com.markatta.akron.expression.CronExpression

import scala.concurrent.duration.FiniteDuration
import scala.collection.immutable.Seq

/**
 * The simple crontab actor looses its state upon stop, does not remember what jobs
 * has already been run. Probably only useful in a single actorsystem.
 */
object SimpleCronTabActor {

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


  // internal stuff

  def props = Props(classOf[SimpleCronTabActor])
}



class SimpleCronTabActor extends Actor with ActorLogging {

  import SimpleCronTabActor._
  import context.dispatcher
  import TimeUtils._

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
      sender() ! Scheduled(id, recipient, message)

    case Trigger(id) =>
      runJob(id)

    case Terminated(actor) =>
      entries.filter(_.job.recipient == actor)
        .foreach { entry =>
          log.info("Removing entry {} because destination actor terminated", entry.job.id)
          removeJob(entry.job.id)
        }

    case UnSchedule(id) =>
      log.info("Unscheduling job {}", id)
      removeJob(id)

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
    updateNext()
  }

  def removeJob(id: UUID): Unit ={
    entries = entries.filterNot(_.job.id != id)
    updateNext()
  }

  def addJob(job: Job): Unit = {
    entries = entries :+ Entry(job, job.when.nextOccurrence)
    updateNext()
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
        Some(context.system.scheduler.scheduleOnce(offsetFromNow, self, Trigger(id)))
      }
      nextScheduled = nextUp.map(entry => (entry.job.id, entry.nextExecutionTime))
    }
  }

  def jobAlreadyExists(recipient: ActorRef, message: Any, when: CronExpression): Boolean =
    entries.exists(e => e.job.recipient == recipient && e.job.message == message && e.job.when == when)

  override def postStop(): Unit = {
    lastScheduled.foreach(_.cancel())
  }
}
