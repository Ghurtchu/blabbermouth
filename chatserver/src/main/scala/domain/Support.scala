package domain

import play.api.libs.json.{JsObject, JsString, Json, Writes}

case class Support(
  supportId: String,
  supportUserName: String,
  user: User,
)

object Support {
  implicit val ws: Writes[Support] =
    (support: Support) =>
      Json.obj(
        "supportId" -> JsString(support.supportId),
        "supportUserName" -> JsString(support.supportUserName),
      ) ++ implicitly[Writes[User]].writes(support.user).as[JsObject]
}
