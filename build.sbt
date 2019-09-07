name := "akron"
version := "2.0-M1"
organization := "com.markatta"
scalaVersion := "2.13.0"
crossScalaVersions := Seq("2.13.0", "2.12.8")

lazy val akkaVersion = "2.6.0-M7"

libraryDependencies ++= Seq(
  "org.scala-lang.modules"      %% "scala-parser-combinators"   % "1.1.2",
  "com.typesafe.akka"           %% "akka-actor-typed"           % akkaVersion,
  "com.typesafe.akka"           %% "akka-serialization-jackson" % akkaVersion,
  "com.typesafe.akka"           %% "akka-persistence-typed"     % akkaVersion % Optional,
  "ch.qos.logback"              % "logback-classic"             % "1.2.3"     % Test,
  "org.scalatest"               %% "scalatest"                  % "3.0.8"     % Test,
  "com.typesafe.akka"           %% "akka-actor-testkit-typed"   % akkaVersion % Test,
  "org.iq80.leveldb"            % "leveldb"                     % "0.7"       % Test,
  "org.fusesource.leveldbjni"   % "leveldbjni-all"              % "1.8"       % Test
)

fork in Test := true // needed because of leveldbjni
fork in run := true
connectInput in run := true

organizationName := "Johan Andrén"
licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt"))

// releasing
releaseCrossBuild := false
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
