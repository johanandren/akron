name := "akron"
organization := "com.markatta"
scalaVersion := "2.11.11"

lazy val akkaVersion = "2.4.19"

libraryDependencies ++= Seq(
  "org.scala-lang.modules"      %% "scala-parser-combinators"  % "1.0.5",
  "com.typesafe.akka"           %% "akka-actor"                % akkaVersion,
  "com.typesafe.akka"           %% "akka-persistence"          % akkaVersion % "optional",
  "org.scalatest"               %% "scalatest"                 % "3.0.1"     % "test",
  "com.typesafe.akka"           %% "akka-testkit"              % akkaVersion % "test",
  "org.iq80.leveldb"            % "leveldb"                    % "0.7"       % "test",
  "org.fusesource.leveldbjni"   % "leveldbjni-all"             % "1.8"       % "test"
)

Protobuf.settings

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
