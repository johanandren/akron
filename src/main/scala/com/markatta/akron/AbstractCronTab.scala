/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.typesafe.com>
 */
package com.markatta.akron

import java.time.LocalDateTime
import java.util.{TimerTask, UUID}

import akka.actor.typed.javadsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.{Actor, ActorLogging, ActorRef}
import com.markatta.akron.CronTab.Entry
import com.markatta.akron.CronTab.Job
import com.markatta.akron.CronTab.Trigger
import com.markatta.akron.CronTab.TriggerTask

import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration

abstract class AbstractCronTab(context: ActorContext[CronTab.Command]) extends AbstractBehavior[CronTab.Command] {



}
