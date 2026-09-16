ThisBuild / scalaVersion := "3.3.6"
ThisBuild / organization := "io.psilicon"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked")

val avroVersion = "1.12.1"
val testSettings = Seq(
  libraryDependencies += "org.scalameta" %% "munit" % "1.0.4" % Test
)

lazy val root = (project in file("."))
  .aggregate(runtime, compiler, javaInterop, fixtures, benchmarks)
  .settings(name := "avrogen", publish / skip := true)

lazy val runtime = (project in file("runtime"))
  .settings(testSettings)
  .settings(name := "avrogen-runtime")

lazy val compiler = (project in file("compiler"))
  .settings(testSettings)
  .settings(
    name := "avrogen-compiler",
    libraryDependencies += "org.apache.avro" % "avro" % avroVersion,
    Compile / mainClass := Some("avrogen.compiler.Main")
  )

lazy val javaInterop = (project in file("java-interop"))
  .dependsOn(runtime)
  .settings(testSettings)
  .settings(
    name := "avrogen-java-interop",
    libraryDependencies += "org.apache.avro" % "avro" % avroVersion
  )

lazy val fixtures = (project in file("fixtures"))
  .dependsOn(runtime, javaInterop % "test->compile")
  .settings(testSettings)
  .settings(
    name := "avrogen-fixtures",
    publish / skip := true,
    Compile / sourceGenerators += Def.task {
      val out = (Compile / sourceManaged).value / "avrogen"
      val input = (Compile / resourceDirectory).value / "avro"
      // Rebuild managed output so removed schemas cannot leave stale classes.
      IO.delete(out)
      (compiler / Compile / runner).value.run(
        "avrogen.compiler.Main",
        (compiler / Compile / fullClasspath).value.files,
        Seq(input.getAbsolutePath, out.getAbsolutePath),
        streams.value.log
      ).get
      (out ** "*.scala").get
    }.taskValue
  )

lazy val benchmarks = (project in file("benchmarks"))
  .dependsOn(fixtures, javaInterop)
  .enablePlugins(JmhPlugin)
  .settings(testSettings)
  .settings(name := "avrogen-benchmarks", publish / skip := true)
