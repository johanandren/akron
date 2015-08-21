package com.markatta.akron

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global

class CronTabSpec extends BaseSpec {

  "The cron tab" should {

    "not return a time if empty" in {
      CronTab().nextExecutionTime should be (None)
    }

    "return the time if there is one" in {
      val when = LocalDateTime.now().plusHours(1)
      val crontab = CronTab().scheduleOneOff("test", when, { println("running") })
      crontab.nextExecutionTime should be (Some(when))
    }

    "return the time closest to now if there is more than one entry" in {
      val inAnHour = LocalDateTime.now().plusHours(1)
      val inTwoHours = inAnHour.plusHours(1)
      val crontab = CronTab()
        .scheduleOneOff("test1", inAnHour, { println("running") })
        .scheduleOneOff("test2", inTwoHours, { println("running") })
      crontab.nextExecutionTime should be (Some(inAnHour))
    }

  }
}
