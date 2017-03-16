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
import java.time.format.DateTimeFormatter
import java.time.temporal.{ChronoField, TemporalField}

import scala.collection.immutable.Seq
import scala.util.Try

/**
 * In lack of a better name, common base type for the classes that
 * describes when to run a cron job
 */
sealed trait CronTrigger {

  /** @return The time when it should run the next time after the 'now' timestamp,
    *         or 'None' if it will never occur again.
    */
  private[akron] def nextTriggerTime(now: LocalDateTime): Option[LocalDateTime]
}

/**
 * Provides a simplified subset of the time expression part of the V7 cron expression standard.
 *
 * Expressions are in the format:
 *
 *
 * ```[minute] [hour] [day of month] [month] [day of week]```
 *
 *
 *  {{{
 *  field         allowed values
 *  -----         --------------
 *  minute        0-59
 *  hour          0-23
 *  day of month  1-31
 *  month         1-12 (or names, see below)
 *  day of week   0-7 (0 or 7 is Sun, or use names)
 *  }}}
 *
 *  Month names are the three first letters, case insensitive: jan, feb etc.
 *  Day names are the three first letters, case insensitive: mon, tue, wed
 *
 *  Both month and days can be defined with numbers as well, months starting with 1 for january
 *  and days 0 for sunday up until 7 for sunday again.
 *
 *  Star '*' means minimum to maximum value (or all values)
 *
 *  Intervals can be defined like this '*\/20' which would mean "every twentieth".
 *
 *  Multiple values can be listed comma separated: '10,20,30'
 */
object CronExpression {

  def apply(expression: String): CronExpression =
    parse(expression).get

  def parse(expression: String): Try[CronExpression] = {
    val parser = new ExpressionParser()
    Try {
      parser.parseAll[CronExpression](parser.expression, expression) match {

        case parser.Success(result: CronExpression, _) =>
          result

        case e: parser.NoSuccess =>
          throw new IllegalArgumentException(s"Invalid cron expression '$expression': $e")

      }
    }
  }

}

@SerialVersionUID(1L)
final case class CronExpression(
   minute: MinuteExpression,
   hour: HourExpression,
   dayOfMonth: DayOfMonthExpression,
   month: MonthExpression,
   dayOfWeek: DayOfWeekExpression) extends CronTrigger {

  {
    val validation = validationError
    if (validation.isDefined) throw new IllegalArgumentException(validation.get)
  }

  /**
   * @return A description of what is wrong if something is wrong
   */
  def validationError: Option[String] = {
    if (!hour.withinRange(0, 23)) Some("Hour out of range")
    else if (!minute.withinRange(0, 59)) Some("Minute out of range")
    else if (!dayOfMonth.withinRange(0, 31)) Some("")
    else if (dayOfMonth != All && dayOfWeek != All) Some("Cannot have both day of month and day of week")
    else None
  }

  /**
   * Worst case performance 525 600 iterations (moving through a full year). Or actually 4 times that
   * if scheduling something on the 28th of february.
   *
   * Could probably do something much more clever here.
   */
  def nextTriggerTime(from: LocalDateTime): Option[LocalDateTime] = {
    val everyMinute = Stream.iterate(from.withSecond(0).withNano(0))((time) =>
      time.plusMinutes(1)
    )

    def dayMatches(time: LocalDateTime): Boolean = (dayOfMonth, dayOfWeek) match {
      case (All, All) => true
      case (dom, All) => dom.matches(time.getDayOfMonth)
      case (All, dow) => dow.matches(time.getDayOfWeek.getValue % 7)
      case _ => throw new IllegalStateException(
        "Both day of month and day of week specified somehow. " +
        "Should never happen because of validation in constructor.")
    }

    everyMinute.find { time =>
      month.matches(time.getMonth.getValue) &&
        dayMatches(time) &&
        hour.matches(time.getHour) &&
        minute.matches(time.getMinute)
    }
  }


  override def toString = s"$minute $hour $dayOfMonth $month $dayOfWeek"

}

trait ExpressionCommons {
  def withinRange(min: Int, max: Int): Boolean
  def matches(n: Int): Boolean
}

sealed trait MinuteExpression extends ExpressionCommons
sealed trait HourExpression extends ExpressionCommons
sealed trait DayOfMonthExpression extends ExpressionCommons
sealed trait MonthExpression extends ExpressionCommons
sealed trait DayOfWeekExpression extends ExpressionCommons

@SerialVersionUID(1L)
final case class Exactly(n: Int)
  extends HourExpression
  with MinuteExpression
  with DayOfMonthExpression
  with MonthExpression
  with DayOfWeekExpression {

  override def matches(n: Int): Boolean = n == this.n

  override def withinRange(min: Int, max: Int): Boolean = n >= min && n <= max

  override def toString: String = n.toString
}
@SerialVersionUID(1L)
final case class Interval(every: Int)
  extends HourExpression
  with MinuteExpression
  with DayOfMonthExpression
  with MonthExpression
  with DayOfWeekExpression {

  override def matches(n: Int): Boolean = n % every == 0
  override def withinRange(min: Int, max: Int): Boolean = every >= min && every <= max

  override def toString: String = s"*/$every"
}

object Many {
  def apply(n1: Int, ns: Int*): Many = new Many(n1 :: ns.toList)
}

/**
 * A set of different time points, for example "0,15,45"
 */
@SerialVersionUID(1L)
final case class Many(times: Seq[Int])
  extends HourExpression
  with MinuteExpression
  with DayOfMonthExpression
  with MonthExpression
  with DayOfWeekExpression {

  override def matches(n: Int): Boolean = times.contains(n)
  override def withinRange(min: Int, max: Int): Boolean = times.forall(n => n >= min && n <= max)

  override def toString: String = times.mkString(",")
}

/**
 * A range that is scoped by it's position/unit, for minute it would allow "0-59" for example.
 */
@SerialVersionUID(1L)
final case class Ranged(times: Range)
  extends HourExpression
  with MinuteExpression
  with DayOfMonthExpression
  with MonthExpression
  with DayOfWeekExpression {

  override def matches(n: Int): Boolean = times.contains(n)
  override def withinRange(min: Int, max: Int): Boolean = times.head >= min && times.last <= max

  override def toString: String = times.toString
}

/**
 * All time points in the unit, the "*" in minute position would trigger each minute when the other
 * units are matching
 */
@SerialVersionUID(1L)
case object All
  extends HourExpression
  with MinuteExpression
  with DayOfMonthExpression
  with MonthExpression
  with DayOfWeekExpression {

  override def withinRange(min: Int, max: Int): Boolean = true

  override def matches(n: Int): Boolean = true

  override def toString: String = "*"
}


object SingleExecution {

  def apply(triggerTime: LocalDateTime): SingleExecution =
    new SingleExecution(triggerTime.`with`(ChronoField.SECOND_OF_MINUTE, 0))
}
/**
 * Execute something at one single point in time, do not repeat.
 * @param triggerTime (seconds are actually ignored, the granularity is minute wise)
 */
@SerialVersionUID(1L)
final class SingleExecution private (private val triggerTime: LocalDateTime) extends CronTrigger {

  override def nextTriggerTime(now: LocalDateTime): Option[LocalDateTime] = {
    val normalizedNow = now.`with`(ChronoField.SECOND_OF_MINUTE, 0)
    if (triggerTime.isBefore(normalizedNow)) None
    else Some(triggerTime)
  }

  override def hashCode(): Int = triggerTime.hashCode()

  override def equals(obj: scala.Any): Boolean = obj match {
    case other: SingleExecution => other.triggerTime.equals(this.triggerTime)
    case _ => false
  }

  override def toString: String = DateTimeFormatter.ISO_DATE_TIME.format(triggerTime)
}