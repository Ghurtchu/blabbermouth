import org.http4s.websocket.WebSocketFrame.Text
import play.api.libs.json.{Json, Reads, Writes}

package object ws {

  implicit class JsonWritesSyntax[A: Writes](self: A) {
    def toJson: String = Json.stringify(Json.toJson(self))
  }

  implicit class JsonReadsSyntax(self: String) {
    def into[A: Reads]: A = Json.parse(self).as[A]
  }

  implicit class WebSocketTextSyntax(self: String) {
    def toText: Text = Text(self)
  }

}
