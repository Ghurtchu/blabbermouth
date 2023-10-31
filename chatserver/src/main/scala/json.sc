import play.api.libs.json.{JsObject, Json}

val json = """{"type":"UserLeft","args":{"chatId":"6837e426a8a640d6847698ed3b914c6e"}}"""

Json.parse(json) match {
  case JsObject(underlying) => println(underlying)
  case _ => println("ra")
}