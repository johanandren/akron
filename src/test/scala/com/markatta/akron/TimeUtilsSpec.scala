package com.markatta.akron

import java.time.{LocalDateTime, Month}

import scala.concurrent.duration._

class TimeUtilsSpec extends BaseSpec {

  "the time utils" should {

    "move a point in time into the next minute" in {
      val point = LocalDateTime.of(2015, 2, 8, 20, 30, 23, 8)
      val result = TimeUtils.moveIntoNextMinute(point)
      result.getYear should be (2015)
      result.getMonth should be (Month.FEBRUARY)
      result.getDayOfMonth should be (8)
      result.getHour should be (20)
      result.getMinute should be (31)
      result.getSecond should be (0)
    }
    
    "calculate the finite duration between two local date times" in {
      val a = LocalDateTime.of(2015, 2, 8, 20, 30, 23, 8)
      val b = LocalDateTime.of(2015, 2, 8, 20, 30, 24, 8)
      val result = TimeUtils.durationBetween(a, b)
      result should be (1.second)
    }

    "order dates local date times ascendingly" in {
      val a = LocalDateTime.of(2015, 2, 8, 20, 30, 23, 8)
      val b = LocalDateTime.of(2015, 2, 8, 20, 30, 24, 8)

      List(a, b).sorted(TimeUtils.ascTimeOrdering)
    }

  }

}
