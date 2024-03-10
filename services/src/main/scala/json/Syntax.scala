package json

import play.api.libs.json.{Json, Reads, Writes}

object Syntax {

  implicit class JsonWritesSyntax[A: Writes](self: A) {
    def toJson: String = Json.stringify(Json.toJson(self))
  }

  implicit class JsonReadsSyntax(self: String) {
    def into[A: Reads]: Either[String, A] =
      scala.util
        .Try(Json.parse(self))
        .fold(
          errors => Left(errors.toString),
          json => Right(json.as[A]),
        )
  }
}
