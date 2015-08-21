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
    FiniteDuration(math.abs(ChronoUnit.SECONDS.between(a, b)), TimeUnit.SECONDS)
  }

  def moveIntoNextMinute(time: LocalDateTime): LocalDateTime = {
    time.withSecond(1).plus(1, ChronoUnit.MINUTES)
  }

}
