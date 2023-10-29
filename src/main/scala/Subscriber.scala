import ChatServer.{JsonSyntax, WebSocketTextSyntax, redisResource}
import cats.effect._
import com.comcast.ip4s.IpLiteralSyntax
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.{RedisChannel, RedisCodec}
import dev.profunktor.redis4cats.pubsub.PubSub
import dev.profunktor.redis4cats.effect.Log.NoOp.instance
import dev.profunktor.redis4cats.effects.{RangeLimit, Score, ScoreWithValue, ZRange}
import fs2.{Pure, Stream}
import fs2.concurrent.{SignallingRef, Topic}
import org.http4s.Method.GET
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.HttpRoutes
import org.http4s.dsl.io
import play.api.libs.json._

import scala.concurrent.duration.{DurationInt, NANOSECONDS}

/** subscribes to Redis pub/sub and forwards messages to UI via WebSockets
  */
object Subscriber extends IOApp.Simple {

  sealed trait Message

  sealed trait In extends Message
  sealed trait Out extends Message

  implicit val writesOut: Writes[Out] = new Writes[Out] {
    override def writes(o: Out): JsValue =
      o match {
        case n: NewUser => writesNewUser.writes(n)
      }
  }

  case object Load extends In
  case class JoinUser(userId: String, username: String, chatId: String) extends In
  implicit val joinUserFmt: Format[JoinUser] = Json.format[JoinUser]

  case class Request(`type`: String, args: Option[In])
  implicit val readsRequest: Reads[Request] = json =>
    for {
      typ <- (json \ "type").validate[String]
      args <- typ match {
        case "JoinUser" => joinUserFmt.reads(json("args")).map(Some(_))
        case "Load"     => JsSuccess(None)
        case _          => JsError("unrecognized request type")
      }
    } yield Request(typ, args)

  // use in Redis PubSub
  case class NewUser(userId: String, username: String, chatId: String) extends Out
  implicit val writesNewUser: Writes[NewUser] = Json.writes[NewUser]

  case class RemoveUser(userId: String)

  case class Response(args: Out)
  implicit val writesResponse: Writes[Response] = response => {
    val `type` = response.args.getClass.getSimpleName
    JsObject(
      Map(
        "type" -> JsString(`type`),
        "args" -> writesOut.writes(response.args),
      ),
    )

  }

  override val run = (for {
    flow <- Resource.eval(Topic[IO, Message])
    redisPubSubConnection <- RedisClient[IO]
      .from("redis://localhost")
      .flatMap(PubSub.mkPubSubConnection[IO, String, String](_, RedisCodec.Utf8))
    redisStream = redisPubSubConnection.subscribe(RedisChannel("joins"))
    _ <- EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"9001")
      .withHttpWebSocketApp(webSocketApp(redisStream, flow, _).orNotFound)
      .withIdleTimeout(60.minutes)
      .build

  } yield ()).useForever
  def webSocketApp(
    redisStream: Stream[IO, String],
    flow: Topic[IO, Message],
    wsb: WebSocketBuilder2[IO],
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case GET -> Root / "joins" =>
      wsb.build(
        send = flow
          .subscribe(10)
          .flatMap {
            case Load =>
              val stream1 = Stream.eval {
                for {
                  users <- redisResource.use { client =>
                    client
                      .zRangeByScore[Int]("users", ZRange(0, 0), None)
                      .map(_.map(WebSocketFrame.Text(_)))
                      .flatTap(values => IO.println(s"Stream Values: $values"))
                      .flatMap(s => IO(Stream.emits[IO, WebSocketFrame.Text](s)))
                  }
                } yield users
              }

              stream1.flatten
            case ju: JoinUser =>
              val json = s"""{"username":"${ju.username}","userId":"${ju.userId}","chatId":"${ju.chatId}"}"""
              val stream1 = Stream.eval {
                redisResource
                  .use(_.zAdd("users", None, ScoreWithValue(Score(1), json)))
              }
              val removeUser = s"""{"type":"RemoveUser","args":{"userId":"${ju.userId}"}}"""

              stream1 >> Stream.emit(removeUser.asText)
          }
          .through(redisStream.map(WebSocketFrame.Text(_)).merge(_)),
        receive = flow.publish
          .compose[Stream[IO, WebSocketFrame]](_.collect { case WebSocketFrame.Text(body, _) =>
            Json
              .parse(body)
              .as[Request]
              .args
              .getOrElse(Load)
          }),
      )
    }
}
