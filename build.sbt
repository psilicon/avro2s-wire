ThisBuild / scalaVersion := "3.3.6"
ThisBuild / organization := "io.psilicon"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked")

val avroVersion = "1.12.1"
val testSettings = Seq(
  libraryDependencies += "org.scalameta" %% "munit" % "1.0.4" % Test
)

lazy val root = (project in file("."))
  .aggregate(runtime, compiler, javaInterop, resolution, fixtures, benchmarks, propertyTests)
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

// Schema evolution is optional: generated matching-schema codecs still need only runtime.
// Jackson parses schema JSON here; Apache Avro is used only as an independent test oracle.
lazy val resolution = (project in file("resolution"))
  .dependsOn(runtime)
  .settings(testSettings)
  .settings(
    name := "avrogen-resolution",
    libraryDependencies ++= Seq(
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.20.0",
      "org.apache.avro" % "avro" % avroVersion % Test
    )
  )

lazy val fixtures = (project in file("fixtures"))
  .dependsOn(runtime, javaInterop % "test->compile", resolution % "test->compile")
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
  .dependsOn(fixtures, javaInterop, resolution)
  .enablePlugins(JmhPlugin)
  .settings(testSettings)
  .settings(name := "avrogen-benchmarks", publish / skip := true)

// Test-only compiler and Java oracle. Generated sources compile against runtime + Scala alone.
lazy val propertyTests = (project in file("property-tests"))
  .dependsOn(runtime, compiler)
  .settings(testSettings)
  .settings(
    name := "avrogen-property-tests",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "org.scalacheck" %% "scalacheck" % "1.18.1" % Test,
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value % Test
    ),
    Test / fork := true,
    Test / parallelExecution := false,
    Test / javaOptions ++= Seq(
      "-Xmx2g",
      "-Davrogen.generated.classpath=" + (runtime / Compile / fullClasspath).value.files.mkString(java.io.File.pathSeparator),
      "-Davrogen.property.target=" + (Test / target).value.getAbsolutePath
    )
  )
