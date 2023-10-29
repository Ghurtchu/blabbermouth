ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.12"

lazy val root = (project in file("."))
  .settings(
    name := "live-chat-support",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % "0.23.23",
      "org.http4s" %% "http4s-ember-client" % "0.23.23",
      "org.http4s" %% "http4s-dsl" % "0.23.23",
      "org.http4s" %% "http4s-circe" % "0.23.18",
      "org.typelevel" %% "cats-effect" % "3.4.8",
      "org.http4s" %% "http4s-circe" % "0.23.18",
      "com.typesafe.play" %% "play-json" % "2.10.1",
      "dev.profunktor" %% "redis4cats-effects" % "1.4.1",
      "dev.profunktor" %% "redis4cats-streams" % "1.4.1"
    )
  )

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", _*) => MergeStrategy.discard
  case _                        => MergeStrategy.first
}

assemblyJarName in assembly := "chatserver.jar"
