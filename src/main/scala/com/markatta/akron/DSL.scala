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

/**
 * Allows for a concise type safe declaration of cron expressions. Example:
 * {{{
 *   import DSL._
 *
 *   val expr = CronExpression(*, * / 20, many(mon, tue, wed), , *)
 * }}}
 */
object DSL {
  import scala.language.implicitConversions

  implicit class AllDecorator(all: All.type) {
    def / (n: Int): Interval = Interval(n)
  }

  val * = All
  def many(n: Int, ns: Int*) = Many(n, ns: _*)
  def many(n: Exactly, ns: Exactly*) = Many(n.n :: ns.map(_.n).toList)

  val mon = Exactly(1)
  val tue = Exactly(2)
  val wed = Exactly(3)
  val thu = Exactly(4)
  val fri = Exactly(5)
  val sat = Exactly(6)
  val sun = Exactly(0)

  val jan = Exactly(1)
  val feb = Exactly(2)
  val mar = Exactly(3)
  val apr = Exactly(4)
  val may = Exactly(5)
  val jun = Exactly(6)
  val jul = Exactly(7)
  val aug = Exactly(8)
  val sep = Exactly(9)
  val oct = Exactly(10)
  val nov = Exactly(11)
  val dec = Exactly(12)



  implicit def exactlyHour(n: Int): HourExpression = Exactly(n)
  implicit def exactlyMinute(n: Int): MinuteExpression = Exactly(n)
  implicit def exactlyDay(n: Int): DayOfMonthExpression = Exactly(n)
  implicit def exactlyMonth(n: Int): MonthExpression = Exactly(n)
  implicit def exactlyDayOfWeek(n: Int): DayOfWeekExpression = Exactly(n)
  implicit def range(r: Range): Ranged = Ranged(r)

  implicit def tuple2Many(t: (Exactly, Exactly)): Many = Many(List(t._1.n, t._2.n))
  implicit def tuple3Many(t: (Exactly, Exactly, Exactly)): Many = Many(List(t._1.n, t._2.n, t._3.n))
  implicit def tuple4Many(t: (Exactly, Exactly, Exactly, Exactly)): Many = Many(List(t._1.n, t._2.n, t._3.n, t._4.n))
}
