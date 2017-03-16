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

import scala.util.parsing.combinator.RegexParsers

private [akron] class ExpressionParser extends RegexParsers {

  implicit class MyRichString(str: String) {
    def ic: Parser[String] = ("""(?i)\Q""" + str + """\E""").r
  }

  def interval: Parser[Interval] = all ~ "/" ~ number ^^ { case _~_~n => Interval(n) }

  def number: Parser[Int] = """\d+""".r ^^ { case d => d.toInt }

  def exactly: Parser[Exactly] = number.map(n => Exactly(n))

  def many: Parser[Many] = number ~ "," ~ rep1sep(number, ",") ^^ { case first ~ _ ~ numbers => Many(first :: numbers) }

  def range: Parser[Ranged] = number ~ "-" ~ number ^^ { case start~_~end => Ranged(start to end) }

  def monthName: Parser[Exactly] =
    "jan".ic ^^ { case _ => Exactly(1) } |
    "feb".ic ^^ { case _ => Exactly(2) } |
    "mar".ic ^^ { case _ => Exactly(3) } |
    "apr".ic ^^ { case _ => Exactly(4) } |
    "may".ic ^^ { case _ => Exactly(5) } |
    "jun".ic ^^ { case _ => Exactly(6) } |
    "jul".ic ^^ { case _ => Exactly(7) } |
    "aug".ic ^^ { case _ => Exactly(8) } |
    "sep".ic ^^ { case _ => Exactly(9) } |
    "oct".ic ^^ { case _ => Exactly(10) } |
    "nov".ic ^^ { case _ => Exactly(11) } |
    "dec".ic ^^ { case _ => Exactly(12) }

  def dayName: Parser[Exactly] =
    "mon".ic ^^ { case _ => Exactly(1) } |
    "tue".ic ^^ { case _ => Exactly(2) } |
    "wed".ic ^^ { case _ => Exactly(3) } |
    "thu".ic ^^ { case _ => Exactly(4) } |
    "fri".ic ^^ { case _ => Exactly(5) } |
    "sat".ic ^^ { case _ => Exactly(6) } |
    "sun".ic ^^ { case _ => Exactly(0) }

  def all: Parser[All.type] = "*" ^^ { case _ => All }

  def hour: Parser[HourExpression] = interval | all | many | range | exactly
  def minute: Parser[MinuteExpression] = interval | all | many | range | exactly
  def dayOfMonth: Parser[DayOfMonthExpression] = interval | all | many | range | exactly
  def month: Parser[MonthExpression] =
    interval |
    all |
    repsep(monthName, ",") ^^ {
      case list if list.size == 1 => list.head
      case list => Many(list.map(_.n))
    } |
    many | range | exactly

  def dayOfWeek: Parser[DayOfWeekExpression] =
    interval |
    all |
    repsep(dayName, ",") ^^ {
      case list if list.size == 1 => list.head
      case list => Many(list.map(_.n))
    } |
    many |
    range |
    exactly.map { case Exactly(n) => Exactly(n % 7)}


  def expression: Parser[CronExpression] = minute ~ hour ~ dayOfMonth ~ month ~ dayOfWeek ^^ {
    case minute~hour~dayOfMonth~month~dayOfWeek => CronExpression(minute, hour, dayOfMonth, month, dayOfWeek)
  }



}
