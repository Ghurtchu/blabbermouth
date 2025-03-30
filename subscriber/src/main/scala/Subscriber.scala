import cats.effect._
import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxOptionId, none}
import com.comcast.ip4s.IpLiteralSyntax
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.{RedisChannel, RedisCodec}
import dev.profunktor.redis4cats.pubsub.PubSub
import dev.profunktor.redis4cats.effect.Log.NoOp.instance
import fs2.{Pure, Stream}
import fs2.concurrent.Topic
import json.Syntax.{JsonReadsSyntax, JsonWritesSyntax}
import ws.{ClientWsMsg, Message, ServerWsMsg}
import ws.ClientWsMsg.rr
import ws.Message.In.{JoinUser, LoadPendingUsers, UnrecognizedMessage}
import ws.Message.In.codecs._
import ws.Message.Out.codecs._
import org.http4s.Method.GET
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.HttpRoutes
import play.api.libs.json._
import users.{PendingUsers, UserStatusManager}
import ws.Message.Out

import scala.concurrent.duration.DurationInt

/** subscribes to Redis pub/sub and forwards messages to UI via WebSockets
  */
object Subscriber extends IOApp.Simple {

  type PubSubMessage = String
  type Subscriber = Stream[IO, PubSubMessage]

  // TODO: parse from config later
  val redisLocation = "redis://redis"

  private def mkSubscriber(
    redis: RedisClient,
    channel: RedisChannel[String],
  ): Resource[IO, Subscriber] =
    PubSub
      .mkPubSubConnection[IO, String, String](redis, RedisCodec.Utf8)
      .map(_.subscribe(channel))

  override val run = (for {
    topic <- Resource.eval(Topic[IO, Message])
    baseRedisClient <- RedisClient[IO].from(redisLocation)
    redisClient <- Redis[IO]
      .fromClient(client = baseRedisClient, codec = RedisCodec.Utf8)
      .flatMap(redis.RedisClient.make[IO])
    subscriber <- mkSubscriber(baseRedisClient, RedisChannel("joins"))
    pendingUsers = PendingUsers.of[IO](redisClient)
    userStatusManager = UserStatusManager.of[IO](redisClient)
    _ <- EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"9001")
      .withHttpWebSocketApp(webSocketApp(subscriber, topic, _, pendingUsers, userStatusManager).orNotFound)
      .withIdleTimeout(60.minutes)
      .build

  } yield ()).useForever

  // TODO: refactor to individual WS connection: /"users"/"id"
  def webSocketApp(
    subscriber: Subscriber,
    topic: Topic[IO, Message],
    wsb: WebSocketBuilder2[IO],
    pendingUsers: PendingUsers[IO],
    userStatusManager: UserStatusManager[IO],
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case GET -> Root / "users" =>
      wsb.build(
        send = topic
          .subscribe(10)
          .evalMapFilter {
            case LoadPendingUsers =>
              for {
                users <- IO.println("Loading pending users...") *> pendingUsers.load
                _ <- IO.println(s"Finished loading pending users: $users")
              } yield Out.PendingUsers(users).some
            case ju: JoinUser =>
              for {
                _ <- IO.println(s"Attempting joining the user: $ju")
                _ <- IO.println(s"Setting status to 'inactive' in Redis for user: $ju")
                _ <- userStatusManager.setInactive(ju.toJson)
                _ <- IO.println(s"removing user from UI")
              } yield Out.RemoveUser(ju.userId).some
            case _ => none.pure[IO]
          }
          .collect { case o: Out => WebSocketFrame.Text(ServerWsMsg(o).toJson) }
          .merge {
            subscriber
              .evalMap { msg =>
                IO.println(s"$msg was consumed from Redis Pub/Sub") as
                  WebSocketFrame.Text(msg)
              }
          },
        receive = topic.publish
          .compose[Stream[IO, WebSocketFrame]](_.evalMap { case WebSocketFrame.Text(body, _) =>
            body
              .as[ClientWsMsg]
              .fold(
                error => IO.println(s"could not deserialize $body: $error") as UnrecognizedMessage,
                wsMsg => IO.pure(wsMsg.args.getOrElse(LoadPendingUsers)),
              )
          }),
      )
    }
}
