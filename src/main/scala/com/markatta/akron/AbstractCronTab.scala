/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.typesafe.com>
 */
package com.markatta.akron

import java.time.LocalDateTime
import java.util.{TimerTask, UUID}

import akka.actor.{Actor, ActorLogging, ActorRef}
import com.markatta.akron.CronTab.Job

import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration

trait AbstractCronTab { _: Actor with ActorLogging =>

  import TimeUtils._

  lazy val settings = new AkronSettings(context.system.settings.config)

  protected final case class Trigger(ids: Seq[(UUID, LocalDateTime)])
  protected final case class Entry(job: Job, nextExecutionTime: LocalDateTime)
  protected final class TriggerTask(msg: Any) extends TimerTask {
    override def run(): Unit = self ! msg
  }

  private var _entries = Vector[Entry]()
  protected final def entries: Vector[Entry] = _entries

  private var nextTrigger: Option[TriggerTask] = None


  private val timer = new java.util.Timer(self.path.toString)

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

  final def addJob(job: Job): Unit =
    job.when.nextTriggerTime(LocalDateTime.now()).fold(
      log.warning("Tried to add job {}", job)
    )(when =>
      _entries = _entries :+ Entry(job, when)
    )

  final def runJob(id: UUID): Unit = {
    _entries = _entries.flatMap {
      case Entry(job @ Job(`id`, recipient, message, expr), _) =>
        log.debug("Running job {} sending '{}' to [{}]", id, message, recipient.path)
        recipient ! message
        // offset the time one minute, so that we do not end up running this instance again
        val laterThanNow = moveIntoNextMinute(LocalDateTime.now())
        job.when.nextTriggerTime(laterThanNow).map(when => Entry(job, when))

      case itsAKeeper => Some(itsAKeeper)
    }
  }

  final def removeJob(id: UUID): Unit ={
    _entries.find(_.job.id == id).foreach { entry =>
      _entries = _entries.filterNot(_ == entry)

      // make sure we don't monitor lifecycle of actors we don't have
      // jobs for
      if (!_entries.exists(_.job.recipient == entry.job.recipient)) {
        context.unwatch(entry.job.recipient)
      }
    }
  }

  def jobAlreadyExists(recipient: ActorRef, message: Any, when: CronExpression): Boolean =
    entries.exists(e => e.job.recipient == recipient && e.job.message == message && e.job.when == when)


  override def postStop(): Unit = {
    timer.cancel()
  }

}
