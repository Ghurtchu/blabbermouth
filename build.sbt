ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.12"

lazy val root = (project in file("."))
  .aggregate(chatserver, subscriber)
  .settings(
    name := "root",
  )

lazy val chatserver = (project in file("chatserver")).settings(
  name := "chatserver",
  libraryDependencies ++= Dependencies.all,
  assemblyJarName in assembly := "chatserver.jar",
  assemblyMergeStrategy in assembly := {
    case PathList("META-INF", _*) => MergeStrategy.discard
    case _ => MergeStrategy.first
  }
)

lazy val subscriber = (project in file("subscriber")).settings(
  name := "subscriber",
  libraryDependencies ++= Dependencies.all,
  assemblyJarName in assembly := "subscriber.jar",
  assemblyMergeStrategy in assembly := {
    case PathList("META-INF", _*) => MergeStrategy.discard
    case _ => MergeStrategy.first
  }
)
