package com.markatta.akron.expression

import java.time.LocalDateTime

import com.markatta.akron.BaseSpec

class ModelSpec extends BaseSpec {

  "the exact model" should {

    "validate range" in {
      Exactly(5).withinRange(0, 5)
    }

    "know that a value matches" in {
      Exactly(5).matches(5) should be(true)
    }

    "know that a value does not match" in {
      Exactly(4).matches(5) should be(false)
    }

  }

  "the interval model" should {
    "validate range" in {
      Interval(5).withinRange(0, 10)
    }

    "know that a value matches" in {
      Interval(20).matches(0) should be(true)
      Interval(20).matches(20) should be(true)
      Interval(20).matches(40) should be(true)
    }

    "know when a value does not match" in {
      Interval(20).matches(1) should be(false)
    }
  }

  "the ranged model" should {
    "validate range" in {
      Ranged(10 to 20).withinRange(0, 23)
    }

    "know that a value matches" in {
      Ranged(10 to 20).matches(10) should be(true)
      Ranged(10 to 20).matches(20) should be(true)
    }

    "know when a value does not match" in {
      Ranged(10 to 20).matches(9) should be(false)
      Ranged(10 to 20).matches(21) should be(false)
    }
  }

  "the many model" should {
    "validate range" in {
      Many(10, 14, 20).withinRange(0, 23)
    }

    "know that a value matches" in {
      Many(10, 14, 20).matches(10) should be(true)
      Many(10, 14, 20).matches(14) should be(true)
      Many(10, 14, 20).matches(20) should be(true)
    }

    "know when a value does not match" in {
      Many(10, 14, 20).matches(9) should be(false)
      Many(10, 14, 20).matches(11) should be(false)
    }
  }

  "the expression" should {

    import DSL._

    "locate the next event from a given time 1" in {
      val from = LocalDateTime.of(2015, 1, 1, 14, 30)

      val expression = CronExpression(20, 30, *, *, *)

      val result = expression.nextOccurrence(from)

      result.getYear shouldEqual 2015
      result.getMonth.getValue shouldEqual 1
      result.getDayOfMonth shouldEqual 1
      result.getHour shouldEqual 20
      result.getMinute shouldEqual 30
    }

    "locate the next event from a given time 2" in {
      val from = LocalDateTime.of(2015, 1, 1, 14, 30)

      val expression = CronExpression(* / 4, 0, *, *, *)

      val result = expression.nextOccurrence(from)

      result.getYear shouldEqual 2015
      result.getMonth.getValue shouldEqual 1
      result.getDayOfMonth shouldEqual 1
      result.getHour shouldEqual 16
      result.getMinute shouldEqual 0
    }

    "locate the next event from a given time 3" in {
      val from = LocalDateTime.of(2015, 1, 1, 14, 30)

      val expression = CronExpression(10, many(15, 25, 35), *, *, tue)

      val result = expression.nextOccurrence(from)

      result.getYear shouldEqual 2015
      result.getMonth.getValue shouldEqual 1
      result.getDayOfMonth shouldEqual 6
      result.getHour shouldEqual 10
      result.getMinute shouldEqual 15
    }
  }

}
