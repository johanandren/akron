import de.heikoseeberger.sbtheader.license.Apache2_0

name := "akron"
organization := "com.markatta.akron"
version := "1.0-SNAPSHOT"
scalaVersion := "2.11.7"

lazy val akkaVersion = "2.4.0-RC1"

libraryDependencies ++= Seq(
  "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.4",
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "org.scalatest" %% "scalatest" % "2.2.4" % "test",
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion
)

fork in run := true
connectInput in run := true

headers := Map(
  "scala" -> Apache2_0("2015", "Johan Andrén")
)