/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.typesafe.com>
 */
package com.markatta.akron

import com.typesafe.config.Config

/**
 * INTERNAL API
 */
class AkronSettings(config: Config) {

  private val akronConf = config.getConfig("akron")

  object PersistentCrontab {
    val SnapshotExpression: String = akronConf.getString("persistent-crontab.snapshot-expression")
  }
}
