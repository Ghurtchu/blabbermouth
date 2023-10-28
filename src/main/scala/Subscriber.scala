import ChatServer.{JsonSyntax, WebSocketTextSyntax, redisResource}
import cats.effect._
import com.comcast.ip4s.IpLiteralSyntax
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.{RedisChannel, RedisCodec}
import dev.profunktor.redis4cats.pubsub.PubSub
import dev.profunktor.redis4cats.effect.Log.NoOp.instance
import dev.profunktor.redis4cats.effects.{RangeLimit, Score, ScoreWithValue, ZRange}
import fs2.{Pure, Stream}
import fs2.concurrent.Topic
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

  case object Subscribe extends Message
  case class JoinUser(userId: String, username: String) extends Message
  implicit val joinUserFmt: Format[JoinUser] = Json.format[JoinUser]

  case class Request(`type`: String, args: Option[Message])
  implicit val readsRequest: Reads[Request] = json =>
    for {
      typ <- (json \ "type").validate[String]
      args <- typ match {
        case "Subscribe" => JsSuccess(None)
        case "JoinUser"  => joinUserFmt.reads(json("args")).map(Some(_))
        case _           => JsError("unrecognized request type")
      }
    } yield Request(typ, args)

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
            case ju: JoinUser =>
              val json = s"""{"username":"${ju.username}","userId":"${ju.userId}"}"""
              Stream.emit(json.asText)
            case Subscribe =>
              val emits: IO[Stream[IO, WebSocketFrame.Text]] = (for {
                users <- redisResource
                  .use(_.zRangeByScore[Int]("users", ZRange(0, 0), Some(RangeLimit(1, 10))))
                  .map(_.map(WebSocketFrame.Text(_)))
                  .flatTap(IO.println)
              } yield users).map(Stream.emits(_))

              Stream.eval(emits).flatMap(identity) concurrently redisStream.map(WebSocketFrame.Text(_))
          },
        receive = flow.publish
          .compose[Stream[IO, WebSocketFrame]](_.collect { case WebSocketFrame.Text(body, _) =>
            Json
              .parse(body)
              .as[Request]
              .args
              .getOrElse(Subscribe)
          }),
      )
    }
}
