package json

import play.api.libs.json.{Json, Reads, Writes}

import scala.util.Try

object Syntax {

  implicit class JsonWritesSyntax[A: Writes](self: A) {
    def toJson: String = (Json.stringify _ compose Json.toJson[A])(self)
  }

  implicit class JsonReadsSyntax(self: String) {
    def as[A: Reads]: Either[String, A] =
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

    def asOpt[A: Reads]: Option[A] = as.toOption
  }
}
