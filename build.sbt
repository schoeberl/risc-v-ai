ThisBuild / scalaVersion := "2.13.18"
ThisBuild / organization := "dev.riscvai"
ThisBuild / version := "0.1.0-SNAPSHOT"

val chiselVersion = "7.13.0"

lazy val root = (project in file("."))
  .settings(
    name := "risc-v-ai",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    ),
    addCompilerPlugin(
      "org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xcheckinit"
    )
  )
