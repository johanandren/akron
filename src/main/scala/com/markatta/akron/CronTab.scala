package com.markatta.akron

import java.time.LocalDateTime

import com.markatta.akron.expression.CronExpression

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}

/**
 * Minimal standalone crontab, does not schedule itself, just provides facilities for
 * keeping track of planned jobs and when they should next run.
 *
 * Instances are immutable so every modifying operation returns a new crontab.
 *
 * Sample usage:
 * {{{
 * import com.markatta.cron.expression.DSL._
 * import com.markatta.cron.CronTab
 * import scala.concurrent.ExecutionContext.Implicits.global
 * val crontab = Crontab()
 *  .schedule(
 *    "send mail",
 *    CronExpression(20, *, (mon, tue, wed), (feb, oct),
 *    { println("send that mail") }
 *  )
 *
 * val nextTime = crontab.nextExecutionTime
 * // somehow trigger execution at that time
 * }}}
 */
object CronTab {

  /**
   * @param name a descriptive name used for logging, in errors etc.
   * @param executionContext execution context to execute the callback on
   */
  sealed abstract class Entry(val name: String, callback: () => Unit, val repeating: Boolean, executionContext: ExecutionContext) {
    /** a textual description of when the entry will run */
    def cronExpression: String

    def nextExecution: LocalDateTime

    def execute(): Future[Unit] =
      Future(callback())(executionContext)

  }

  /** a cron expression based entry */
  private class ExpressionEntry(
    name: String,
    expression: CronExpression,
    val nextExecution: LocalDateTime,
    callback: () => Unit,
    executionContext: ExecutionContext) extends Entry(name, callback, true, executionContext) {

    def cronExpression = expression.toString

  }

  /** an entry that will run once, at a specific time */
  private class OneOffEntry(
    name: String,
    when: LocalDateTime,
    callback: () => Unit,
    executionContext: ExecutionContext) extends Entry(name, callback, false, executionContext) {

    def nextExecution = when
    def cronExpression = when.toString

  }

  implicit val ascTimeOrdering: Ordering[LocalDateTime] = Ordering.fromLessThan((a, b) =>
    a.isBefore(b)
  )

  val empty = new CronTab()
  def apply(): CronTab = empty

}


final class CronTab private (unsortedEntries: Seq[CronTab.Entry] = Seq.empty, val history: List[(String, LocalDateTime)] = Nil) {

  import CronTab._

  val entries = unsortedEntries.sortBy(_.nextExecution)

  def schedule(name: String, expression: CronExpression, callback: => Unit)(implicit ec: ExecutionContext): CronTab = {
    val nextTime = expression.nextOccurrence(LocalDateTime.now())
    new CronTab(entries :+ new ExpressionEntry(name, expression, nextTime, () => callback, ec), history)
  }

  def scheduleOneOff(name: String, when: LocalDateTime, callback: => Unit)(implicit ec: ExecutionContext): CronTab = {
    new CronTab(entries :+ new OneOffEntry(name, when, () => callback, ec), history)
  }

  /**
   * @return The time to invoke execute next to run the next up scheduled block
   */
  def nextExecutionTime: Option[LocalDateTime] = entries.headOption.map(_.nextExecution)

  /**
   * Execute the next up crontab entry, regardless of scheduled time. You are responsible to
   * use [[nextExecutionTime]] to figure out when to execute the next time. If there are more
   * jobs that should execute in the exact same time order is not guaranteed and only one of
   * them will be executed.
   *
   * @return A new crontab updated with the state after the given and the future completion of the next job
   *         (which is useful to handle errors, time completion etc.)
   * TODO how to make sure we do not execute it again the same minute
   */
  def executeNext(): (CronTab, Future[Unit]) =
    entries.headOption.fold[(CronTab, Future[Unit])](
      // empty tab
      (this, Future.successful(Unit))
    ) ( head =>
      (
        new CronTab(
          if (head.repeating) entries
          else entries.tail,
          (head.name, LocalDateTime.now()) :: history
        ),
        head.execute()
      )
    )

}