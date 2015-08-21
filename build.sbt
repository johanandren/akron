name := "akron"
organization := "com.markatta.akron"
version := "1.0-SNAPSHOT"
scalaVersion := "2.11.7"

lazy val akkaVersion = "2.3.12"

libraryDependencies ++= Seq(
  "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.4",
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "org.scalatest" %% "scalatest" % "2.2.4" % "test"
)

fork in run := true
connectInput in run := true
