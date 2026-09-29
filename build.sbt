ThisBuild / scalaVersion := "3.3.7"
ThisBuild / organization := "dev.scalapds"
ThisBuild / version := "0.1.0-SNAPSHOT"

val http4sVersion = "0.23.37"
val circeVersion = "0.14.10"

lazy val root = (project in file("."))
  .settings(
    name := "scala-pds",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Werror",
      "-language:implicitConversions"),
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "org.xerial" % "sqlite-jdbc" % "3.47.1.0",
      "org.postgresql" % "postgresql" % "42.7.5",
      "com.zaxxer" % "HikariCP" % "6.2.1",
      "org.bouncycastle" % "bcprov-jdk18on" % "1.79",
      "com.yubico" % "webauthn-server-core" % "2.9.0",
      // Yubico declares open ranges; pin them so builds stay reproducible.
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.18.2",
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % "2.18.2",
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % "2.18.2",
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % "2.18.2",
      "com.google.guava" % "guava" % "33.4.0-jre",
      "dnsjava" % "dnsjava" % "3.6.3",
      "redis.clients" % "jedis" % "5.2.0",
      "org.slf4j" % "slf4j-simple" % "2.0.17",
      "org.typelevel" %% "munit-cats-effect" % "2.0.0" % Test
    ),
    Compile / run / fork := true,
    assembly / mainClass := Some("pds.Main"),
    assembly / assemblyJarName := "scala-pds.jar",
    assembly / assemblyMergeStrategy := {
      // dnsjava's resolver SPI would replace the JVM default; we call it directly.
      case PathList("META-INF", "services", "java.net.spi.InetAddressResolverProvider") =>
        MergeStrategy.discard
      case PathList("META-INF", "services", _*)      => MergeStrategy.concat
      case PathList("META-INF", "versions", _*)      => MergeStrategy.first
      case PathList("META-INF", _*)                  => MergeStrategy.discard
      case PathList("module-info.class")             => MergeStrategy.discard
      case "reference.conf" | "application.conf"     => MergeStrategy.concat
      case _                                         => MergeStrategy.first
    },
    Test / fork := true,
    Test / javaOptions += "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn"
  )
