import cats.effect._
import com.comcast.ip4s.IpLiteralSyntax
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.SortedSetCommands
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.{RedisChannel, RedisCodec}
import dev.profunktor.redis4cats.pubsub.PubSub
import dev.profunktor.redis4cats.effect.Log.NoOp.instance
import dev.profunktor.redis4cats.effects.{Score, ScoreWithValue, ZRange}
import fs2.Stream
import fs2.concurrent.Topic
import org.http4s.Method.GET
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.HttpRoutes
import play.api.libs.json._

import scala.concurrent.duration.DurationInt

/** subscribes to Redis pub/sub and forwards messages to UI via WebSockets
  */
object Subscriber extends IOApp.Simple {

  val redis: Resource[IO, SortedSetCommands[IO, String, String]] =
    RedisClient[IO].from("redis://redis").flatMap(Redis[IO].fromClient(_, RedisCodec.Utf8))

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
      .from("redis://redis")
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
    redisPubSub: Stream[IO, String],
    flow: Topic[IO, Message],
    wsb: WebSocketBuilder2[IO],
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case GET -> Root / "joins" =>
      wsb.build(
        send = flow
          .subscribe(10)
          .flatMap {
            case Load =>
              val pendingUsers: IO[Stream[IO, WebSocketFrame.Text]] = for {
                _ <- IO.println("Loading pending users...")
                users <- redis.use {
                  _.zRangeByScore[Int]("users", ZRange(0, 0), None)
                    .map(_.map(WebSocketFrame.Text(_)))
                    .flatTap(u => IO.println(s"Finished loading pending users: $u"))
                    .flatMap(frames => IO(Stream.emits(frames)))
                }
              } yield users

              Stream.eval(pendingUsers).flatten
            case ju: JoinUser =>
              val response: IO[WebSocketFrame.Text] = for {
                _ <- IO.println(s"Attempting joining the user: $ju")
                json = Json.prettyPrint(Json.toJson(ju))
                _ <- redis.use(_.zAdd("users", None, ScoreWithValue(Score(1), json)))
                removeUserFromClient = s"""{"type":"RemoveUser","args":{"userId":"${ju.userId}"}}"""
                _ <- IO.println(s"sending back $removeUserFromClient")
              } yield WebSocketFrame.Text(removeUserFromClient)

              Stream.eval(response)
          }
          .through { stream =>
            redisPubSub
              .map { newUser =>
                println(s"$newUser was consumed from pub/sub channel")
                WebSocketFrame.Text(newUser)
              }
              .merge(stream)
          },
        receive = flow.publish
          .compose[Stream[IO, WebSocketFrame]](_.collect { case WebSocketFrame.Text(body, _) =>
            println(s"received message: $body")
            val result = Json
              .parse(body)
              .as[Request]
              .args
              .getOrElse(Load)
            println(s"parsed message to: $result")

            result
          }),
      )
    }
}
