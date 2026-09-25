ThisBuild / scalaVersion := "3.3.8"
ThisBuild / organization := "io.psilicon"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked")

ThisBuild / organizationName := "psilicon"
ThisBuild / organizationHomepage := Some(url("https://github.com/psilicon"))
ThisBuild / description := "Scala 3 Avro schema compiler and native binary runtime"
ThisBuild / homepage := Some(url("https://github.com/psilicon/avro2s-wire"))
ThisBuild / licenses := List("Apache 2" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / scmInfo := Some(ScmInfo(
  url("https://github.com/psilicon/avro2s-wire"),
  "scm:git:git@github.com:psilicon/avro2s-wire.git"
))
ThisBuild / developers := List(Developer(
  "psilicon", "Psilicon", "hello@psilicon.io", url("https://github.com/psilicon")
))
ThisBuild / pomIncludeRepository := { _ => false }
// sbt stages signed artifacts locally; the release workflow uploads them with sonaUpload.
ThisBuild / publishTo := localStaging.value
pgpPassphrase := sys.env.get("GPG_PASSPHRASE").map(_.toArray)

val avroVersion = "1.12.1"
val confluentVersion = "8.1.5"
ThisBuild / resolvers += "Confluent" at "https://packages.confluent.io/maven/"
val testSettings = Seq(
  libraryDependencies += "org.scalameta" %% "munit" % "1.0.4" % Test
)

lazy val root = (project in file("."))
  .aggregate(runtime, compiler, javaBackend, resolution, schemaRegistry, fixtures, benchmarks, propertyTests)
  .settings(name := "avro2s-wire", publish / skip := true)

lazy val runtime = (project in file("runtime"))
  .settings(testSettings)
  .settings(
    name := "avro2s-wire-runtime",
    Compile / javacOptions ++= Seq("--release", "11")
  )

lazy val compiler = (project in file("compiler"))
  .settings(testSettings)
  .settings(
    name := "avro2s-wire-compiler",
    libraryDependencies += "org.apache.avro" % "avro" % avroVersion,
    Compile / mainClass := Some("avro2s.wire.compiler.Main")
  )

lazy val javaBackend = (project in file("java-backend"))
  .dependsOn(runtime)
  .settings(testSettings)
  .settings(
    name := "avro2s-wire-java-backend",
    libraryDependencies += "org.apache.avro" % "avro" % avroVersion
  )

// Schema evolution is optional: generated matching-schema codecs still need only runtime.
// Jackson parses schema JSON here; Apache Avro is used only as an independent test oracle.
lazy val resolution = (project in file("resolution"))
  .dependsOn(runtime)
  .settings(testSettings)
  .settings(
    name := "avro2s-wire-resolution",
    libraryDependencies ++= Seq(
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.20.0",
      "org.apache.avro" % "avro" % avroVersion % Test
    )
  )

lazy val fixtures = (project in file("fixtures"))
  .dependsOn(runtime, javaBackend % "test->compile", resolution % "test->compile")
  .settings(testSettings)
  .settings(
    name := "avro2s-wire-fixtures",
    publish / skip := true,
    Compile / sourceGenerators += Def.task {
      val out = (Compile / sourceManaged).value / "avro2s-wire"
      val input = (Compile / resourceDirectory).value / "avro"
      // Rebuild managed output so removed schemas cannot leave stale classes.
      IO.delete(out)
      (compiler / Compile / runner).value.run(
        "avro2s.wire.compiler.Main",
        (compiler / Compile / fullClasspath).value.files,
        Seq(input.getAbsolutePath, out.getAbsolutePath),
        streams.value.log
      ).get
      (out ** "*.scala").get
    }.taskValue
  )

// Registry networking is optional; generated values still use Wire's native codecs.
lazy val schemaRegistry = (project in file("schema-registry"))
  .dependsOn(resolution, fixtures % "test->compile")
  .settings(testSettings)
  .settings(
    name := "avro2s-wire-schema-registry",
    libraryDependencies ++= Seq(
      "io.confluent" % "kafka-schema-registry-client" % confluentVersion,
      "org.apache.avro" % "avro" % avroVersion,
      "io.confluent" % "kafka-avro-serializer" % confluentVersion % Test
    )
  )

// Explicitly invoked against an isolated real registry; ordinary `test` needs no Docker.
lazy val registryIntegration = (project in file("registry-integration"))
  .dependsOn(schemaRegistry, fixtures)
  .settings(testSettings)
  .settings(
    name := "avro2s-wire-registry-integration",
    publish / skip := true,
    Test / fork := true,
    Test / parallelExecution := false,
    libraryDependencies += "io.confluent" % "kafka-avro-serializer" % confluentVersion % Test
  )

lazy val benchmarks = (project in file("benchmarks"))
  .dependsOn(fixtures, javaBackend, resolution)
  .enablePlugins(JmhPlugin)
  .settings(testSettings)
  .settings(name := "avro2s-wire-benchmarks", publish / skip := true)

// Test-only compiler and Java oracle. Generated sources compile against runtime + Scala alone.
lazy val propertyTests = (project in file("property-tests"))
  .dependsOn(runtime, compiler, resolution % "test->compile")
  .settings(testSettings)
  .settings(
    name := "avro2s-wire-property-tests",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "org.scalacheck" %% "scalacheck" % "1.18.1" % Test,
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value % Test
    ),
    Test / fork := true,
    Test / parallelExecution := false,
    Test / javaOptions ++= Seq(
      "-Xmx2g",
      "-Davro2s.wire.generated.classpath=" + (runtime / Compile / fullClasspath).value.files.mkString(java.io.File.pathSeparator),
      "-Davro2s.wire.property.target=" + (Test / target).value.getAbsolutePath
    )
  )
