import sbt._
object Dependencies {

  val all = Seq(
    "org.http4s" %% "http4s-ember-server" % "0.23.23",
    "org.http4s" %% "http4s-ember-client" % "0.23.23",
    "org.http4s" %% "http4s-dsl" % "0.23.23",
    "org.http4s" %% "http4s-circe" % "0.23.18",
    "org.typelevel" %% "cats-effect" % "3.4.8",
    "org.http4s" %% "http4s-circe" % "0.23.18",
    "com.typesafe.play" %% "play-json" % "2.10.1",
    "dev.profunktor" %% "redis4cats-effects" % "1.4.1",
    "dev.profunktor" %% "redis4cats-streams" % "1.4.1",
  )
}
