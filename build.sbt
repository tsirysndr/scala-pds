ThisBuild / scalaVersion := "3.3.7"
ThisBuild / organization := "dev.scalapds"
ThisBuild / version := "0.1.0-SNAPSHOT"

val http4sVersion = "0.23.37"
val circeVersion = "0.14.10"

lazy val root = (project in file("."))
  .settings(
    name := "scala-pds",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Werror"),
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
      "dnsjava" % "dnsjava" % "3.6.3",
      "org.slf4j" % "slf4j-simple" % "2.0.17",
      "org.typelevel" %% "munit-cats-effect" % "2.0.0" % Test
    ),
    Compile / run / fork := true,
    Test / fork := true,
    Test / javaOptions += "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn"
  )
