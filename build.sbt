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
  ).enablePlugins(DockerPlugin)

lazy val chatserver = (project in file("chatserver"))
  .settings(
    name := "chatserver",
    assembly / assemblyJarName := "chatserver.jar",
    assembly / mainClass := Some("ChatServer"), // Replace "com.example.MainClass" with the fully qualified name of your main class
  )
  .settings(commonSettings)
  .enablePlugins(DockerPlugin)

lazy val subscriber = (project in file("subscriber"))
  .settings(
    name := "subscriber",
    assembly / assemblyJarName := "subscriber.jar",
    assembly / mainClass := Some("Subscriber"),
  )
  .settings(commonSettings)
  .enablePlugins(DockerPlugin)

