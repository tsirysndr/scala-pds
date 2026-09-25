ThisBuild / scalaVersion := "3.3.7"
ThisBuild / organization := "dev.scalapds"
ThisBuild / version := "0.1.0-SNAPSHOT"

val http4sVersion = "0.23.37"

lazy val root = (project in file("."))
  .settings(
    name := "scala-pds",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Werror"),
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "org.typelevel" %% "munit-cats-effect" % "2.0.0" % Test,
      "org.slf4j" % "slf4j-simple" % "2.0.17"
    ),
    Compile / run / fork := true
  )
