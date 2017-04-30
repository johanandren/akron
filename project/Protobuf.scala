import sbt._
import Keys._
import sbt.{AutoPlugin, SettingKey, TaskKey}


object Protobuf {

  object Keys {
    val protobufVersion = SettingKey[String]("protobuf-version")
    val protoc = TaskKey[Unit]("protoc")
  }

  import Keys._
  lazy val settings = Seq(
    protobufVersion := "3.2.0",
    libraryDependencies += "com.google.protobuf" % "protobuf-java" % protobufVersion.value,
    protoc := {
      val protoSources = (sourceDirectory in Compile).value / "protobuf"
      val outputPath = (sourceDirectory in Compile).value / "java"
      if (!outputPath.exists()) outputPath.mkdirs()

      // double check version
      val res = Seq("protoc", "--version").lines.head
      val version = res.split(" ").last.trim
      val expectedVersion = protobufVersion.value
      if (version != expectedVersion) {
        throw new RuntimeException(s"Wrong protoc version! Expected $expectedVersion but got $version")
      }

      // actually compile
      val protofiles = (protoSources ** "*.proto").get
      val compile = Seq("protoc", s"-I${protoSources.absolutePath}", s"--java_out=${outputPath.absolutePath}") ++
        protofiles.map(_.getAbsolutePath)
      println(compile.mkString(","))
      compile.lines(streams.value.log)
    }

  )

}