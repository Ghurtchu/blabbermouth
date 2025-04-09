ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.12"

val commonSettings = Seq(
  assembly / assemblyMergeStrategy := {
    case PathList("META-INF", _*) => MergeStrategy.discard
    case _                        => MergeStrategy.first
  },
)

lazy val root = (project in file("."))
  .dependsOn(chatserver, subscriber)
  .aggregate(chatserver, subscriber)
  .settings(name := "root")
  .settings(commonSettings)
  .settings(addCompilerPlugin(Dependencies.BetterMonadicFor))

lazy val domain = (project in file("domain"))
  .settings(
    name := "domain",
    libraryDependencies := Dependencies.Json,
  )
  .settings(commonSettings)

lazy val services = (project in file("services"))
  .settings(
    name := "services",
    libraryDependencies := Dependencies.Services,
  )
  .dependsOn(domain)

lazy val chatserver = (project in file("chatserver"))
  .settings(
    name := "chatserver",
    assembly / assemblyJarName := "chatserver.jar",
    assembly / mainClass := Some("ChatServer"),
    libraryDependencies := Dependencies.ChatServer,
  )
  .settings(commonSettings)
  .settings(addCompilerPlugin(Dependencies.BetterMonadicFor))
  .dependsOn(services)

lazy val subscriber = (project in file("subscriber"))
  .settings(
    name := "subscriber",
    assembly / assemblyJarName := "subscriber.jar",
    assembly / mainClass := Some("Subscriber"),
    libraryDependencies := Dependencies.Subscriber,
  )
  .settings(commonSettings)
  .settings(addCompilerPlugin(Dependencies.BetterMonadicFor))
  .dependsOn(services)
