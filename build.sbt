import _root_.sbtrelease.ReleasePlugin.ReleaseKeys
import _root_.sbtrelease.ReleasePlugin._
import _root_.xerial.sbt.Sonatype._
import de.heikoseeberger.sbtheader.license.Apache2_0

name := "akron"
organization := "com.markatta"
scalaVersion := "2.11.7"

lazy val akkaVersion = "2.4.0-RC1"

libraryDependencies ++= Seq(
  "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.4",
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "org.scalatest" %% "scalatest" % "2.2.4" % "test",
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test"
)

fork in run := true
connectInput in run := true

headers := Map(
  "scala" -> Apache2_0("2015", "Johan Andrén")
)

// releasing
releaseSettings
sonatypeSettings
ReleaseKeys.crossBuild := false
licenses := Seq("Apache License, Version 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))
homepage := Some(url("https://github.com/johanandren/akron"))
publishMavenStyle := true
publishArtifact in Test := false
pomIncludeRepository := { _ => false }
publishTo := Some {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    "snapshots" at nexus + "content/repositories/snapshots"
  else
    "releases" at nexus + "service/local/staging/deploy/maven2"
}

pomExtra :=
  <scm>
    <url>git@github.com:johanandren/akron.git</url>
    <connection>scm:git:git@github.com:johanandren/akron.git</connection>
  </scm>
    <developers>
      <developer>
        <id>johanandren</id>
        <name>Johan Andrén</name>
        <email>johan@markatta.com</email>
        <url>https://markatta.com/johan/codemonkey</url>
      </developer>
    </developers>