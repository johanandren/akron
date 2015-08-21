package com.markatta.akron.expression

import com.markatta.akron.BaseSpec

import scala.util.{Failure, Success}

class ExpressionParserSpec extends BaseSpec {

  "the expression parser" should {

    import DSL._

    "parse * wildcards" in {
      CronExpression.parse("* * * * *") shouldEqual
        Success(CronExpression(*, *, *, *, *))
    }

    "parse a simple hour minute expression" in {
      CronExpression.parse("19 30 * * *") shouldEqual
        Success(CronExpression(19, 30, *, *, *))
    }

    "parse a simple expression with month and day of month" in {
      CronExpression.parse("19 30 20 jan *") shouldEqual
        Success(CronExpression(19, 30, 20, 1, *))
    }

    "parse a simple expression with day of week" in {
      CronExpression.parse("19 30 * jan mon") shouldEqual
        Success(CronExpression(19, 30, *, 1, 1))
    }

    "fail if both day of month and day of week is provided" in {
      CronExpression.parse("19 30 20 jan mon") shouldBe a[Failure[_]]
    }

    "parse a list of values" in {
      CronExpression.parse("19,20 30,40 * jan,feb mon,tue") shouldBe
        Success(CronExpression(many(19,20), many(30, 40), *, many(1,2), many(1, 2)))
    }

    "ignore case" in {
      CronExpression.parse("* * * JAN,Feb,mar Mon,TUE,wed") shouldBe
        Success(CronExpression(*, *, *, many(1,2,3), many(1,2,3)))
    }

    "parse a range" in {
      CronExpression.parse("15-20 20-30 12-15 * *") shouldBe
        Success(CronExpression(15 to 20, 20 to 30, 12 to 15, *, *))
    }

    "parse a star-slash expression" in {
      CronExpression.parse("*/20 */30 */2 * *") shouldBe
        Success(CronExpression(* / 20, * / 30, * / 2, *, *))
    }

  }

}
