package com.markatta.akron

import akka.actor.testkit.typed.scaladsl.LogCapturing
import org.scalatest.{Matchers, WordSpec}

abstract class BaseSpec extends WordSpec with Matchers with LogCapturing
