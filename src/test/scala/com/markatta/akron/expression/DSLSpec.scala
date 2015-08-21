package com.markatta.akron.expression

import com.markatta.akron.BaseSpec

class DSLSpec extends BaseSpec {

  import DSL._

  // just try to cover all syntax variations here
  CronExpression(20, *, (mon, tue, wed), (feb, oct), *)
  CronExpression(20, *, * / 20, 10 to 12, *)

  "the expression dsl" should {
    "just compile" in {
      true should be (true)
    }
  }

}
