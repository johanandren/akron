import _root_.sbtrelease.ReleasePlugin.ReleaseKeys
import _root_.sbtrelease.ReleasePlugin._
import _root_.xerial.sbt.Sonatype._
import de.heikoseeberger.sbtheader.license.Apache2_0

name := "akron"
organization := "com.markatta"
scalaVersion := "2.11.11"

lazy val akkaVersion = "2.4.17"

libraryDependencies ++= Seq(
  "org.scala-lang.modules"      %% "scala-parser-combinators"  % "1.0.5",
  "com.typesafe.akka"           %% "akka-actor"                % akkaVersion,
  "com.typesafe.akka"           %% "akka-persistence"          % akkaVersion % "optional",
  "org.scalatest"               %% "scalatest"                 % "2.2.4"     % "test",
  "com.typesafe.akka"           %% "akka-testkit"              % akkaVersion % "test",
  "org.iq80.leveldb"            % "leveldb"                    % "0.7"       % "test",
  "org.fusesource.leveldbjni"   % "leveldbjni-all"             % "1.8"       % "test"
)

Protobuf.settings

fork in Test := true // needed because of leveldbjni
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