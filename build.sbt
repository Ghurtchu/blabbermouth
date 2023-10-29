ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.12"

val commonSettings = Seq(
  libraryDependencies ++= Dependencies.all,
  assembly / assemblyMergeStrategy := {
    case PathList("META-INF", _*) => MergeStrategy.discard
    case _                        => MergeStrategy.first
  },
)

lazy val root = (project in file("."))
  .aggregate(chatserver, subscriber)
  .settings(
    name := "root",
  )

lazy val chatserver = (project in file("chatserver"))
  .settings(
    name := "chatserver",
    assembly / assemblyJarName := "chatserver.jar",
  )
  .settings(commonSettings)

lazy val subscriber = (project in file("subscriber"))
  .settings(
    name := "subscriber",
    assemblyJarName in assembly := "subscriber.jar",
  )
  .settings(commonSettings)
