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
import java.time.temporal.ChronoUnit

import scala.concurrent.duration.FiniteDuration

private[akron] object TimeUtils {


  implicit val ascTimeOrdering: Ordering[LocalDateTime] = Ordering.fromLessThan((a, b) =>
    a.isBefore(b)
  )

  def durationUntil(time: LocalDateTime): FiniteDuration = durationBetween(time, LocalDateTime.now())

  def durationBetween(a: LocalDateTime, b: LocalDateTime): FiniteDuration = {
    import java.util.concurrent.TimeUnit
    FiniteDuration(math.abs(ChronoUnit.MILLIS.between(a, b)), TimeUnit.MILLISECONDS)
  }

  def moveIntoNextMinute(time: LocalDateTime): LocalDateTime = {
    time.withSecond(0).plus(1, ChronoUnit.MINUTES)
  }

}
