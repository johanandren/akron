package com.markatta.akron

import scala.util.{Failure, Success}

class ExpressionParserSpec extends BaseSpec {

  "the expression parser" should {

    import DSL._

    "parse * wildcards" in {
      CronExpression.parse("* * * * *") shouldEqual
        Success(CronExpression(*, *, *, *, *))
    }

    "parse a simple hour minute expression" in {
      CronExpression.parse("30 19 * * *") shouldEqual
        Success(CronExpression(30, 19, *, *, *))
    }

    "parse a simple expression with month and day of month" in {
      CronExpression.parse("30 19 20 jan *") shouldEqual
        Success(CronExpression(30, 19, 20, 1, *))
    }

    "parse a simple expression with day of week" in {
      CronExpression.parse("30 19 * jan mon") shouldEqual
        Success(CronExpression(30, 19, *, 1, 1))
    }

    "fail if both day of month and day of week is provided" in {
      CronExpression.parse("30 19 20 jan mon") shouldBe a[Failure[_]]
    }

    "parse a list of values" in {
      CronExpression.parse("30,40 19,20 * jan,feb mon,tue") shouldBe
        Success(CronExpression(many(30, 40), many(19,20), *, many(1,2), many(1, 2)))
    }

    "ignore case" in {
      CronExpression.parse("* * * JAN,Feb,mar Mon,TUE,wed") shouldBe
        Success(CronExpression(*, *, *, many(1,2,3), many(1,2,3)))
    }

    "parse a range" in {
      CronExpression.parse("20-30 15-20 12-15 * *") shouldBe
        Success(CronExpression(20 to 30, 15 to 20, 12 to 15, *, *))
    }

    "parse a star-slash expression" in {
      CronExpression.parse("*/30 */20 */2 * *") shouldBe
        Success(CronExpression(* / 30, * / 20, * / 2, *, *))
    }

  }

}
