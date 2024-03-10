package json

import play.api.libs.json.{Json, Reads, Writes}

import scala.util.Try

object Syntax {

  implicit class JsonWritesSyntax[A: Writes](self: A) {
    def toJson: String = Json.stringify(Json.toJson(self))
  }

  implicit class JsonReadsSyntax(self: String) {
    def into[A: Reads]: Either[String, A] =
      Try(Json.parse(self))
        .fold(
          error => Left(error.toString),
          json =>
            Try(json.as[A])
              .fold(
                error => Left(error.toString),
                Right(_),
              ),
        )
  }
}
