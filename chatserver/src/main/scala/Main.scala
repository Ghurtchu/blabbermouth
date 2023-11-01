import ChatServer._
import play.api.libs.json.Json

object Main extends App {

  val json = Joined(User, "123", "awdaw", None, None)

  Json.toJson(json)

  val pubSubJson = Map("type" -> "UserJoined".asJson, "args" -> json.asJson).asJson

  println(pubSubJson)

  println(json.asJson)

  println(Json.prettyPrint(Json.toJson(Map("type" -> "UserJoined".asJson, "args" -> json.asJson))))
}
