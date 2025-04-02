import cats.effect._
import cats.effect.std.Console
import cats.syntax.all._
import ws.{ClientWsMsg, Message, PingPong, ServerWsMsg}
import ws.Message.Out.codecs._
import ws.Message.In._
import ws.Message.Out._
import com.comcast.ip4s.IpLiteralSyntax
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.{RedisChannel, RedisCodec}
import dev.profunktor.redis4cats.effect.Log.NoOp.instance
import fs2.{Pipe, Stream}
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import play.api.libs.json._
import dev.profunktor.redis4cats.pubsub.PubSub
import fs2.concurrent.Topic
import ws.Message._
import ws._
import org.http4s.websocket.WebSocketFrame.Text
import redis.{PubSubMessage, RedisPublisher}
import PubSubMessage.Args.codecs._
import PubSubMessage._
import cats.Applicative
import cats.data.OptionT
import domain.{ChatParticipant, User}
import json.Syntax.{JsonReadsSyntax, JsonWritesSyntax}
import org.http4s.server.middleware.CORS
import users.UserStatusManager

import scala.concurrent.duration.DurationInt

/**   - publishes Join message to Redis pub/sub
  *   - serves chat functionality via WebSockets
  */
object ChatServer extends IOApp.Simple {

  type UserId = String
  type ChatId = String
  type WebSocketFrames = Stream[IO, WebSocketFrame]
  type Flow[A] = Pipe[IO, A, Unit]
  type WebSocketFrameFlow = Flow[WebSocketFrame]

  // TODO: parse from config later
  val redisLocation = "redis://redis"

  def generateRandomId: IO[String] = IO.delay {
    java.util.UUID
      .randomUUID()
      .toString
      .replaceAll("-", "")
  }

  private def mkRef[K, V]: Resource[IO, Ref[IO, Map[K, V]]] =
    Resource.eval(IO.ref(Map.empty[K, V]))

  val run = (for {
    redisClientAndPublisher <- RedisClient[IO]
      .from(strUri = redisLocation)
      .flatMap { redisClient =>
        for {
          client <- Redis[IO]
            .fromClient(client = redisClient, codec = RedisCodec.Utf8)
            .flatMap(redis.RedisClient.make[IO])
          publisher <- PubSub
            .mkPubSubConnection[IO, String, String](client = redisClient, codec = RedisCodec.Utf8)
            .map(_.publish(RedisChannel("joins")))
            .flatMap(redis.RedisPublisher.make[IO])
        } yield (client, publisher)
      }
    (redisClient, redisPublisher) = redisClientAndPublisher
    userStatusManager = UserStatusManager.of[IO](redisClient)
    chatHistoryRef <- mkRef[UserId, ChatHistory]
    chatTopicRef <- mkRef[ChatId, Topic[IO, Message]]
    pingPongRef <- mkRef[ChatId, PingPong]
    _ <- EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"9000")
      .withHttpWebSocketApp(webSocketApp(_, userStatusManager, chatHistoryRef, redisPublisher, pingPongRef, chatTopicRef))
      .withIdleTimeout(300.seconds)
      .build
  } yield ExitCode.Success).useForever

  private def findById[A](cache: Ref[IO, Map[String, A]])(id: String): IO[Option[A]] =
    cache.get.map(_.get(id))

  private def updatePingPongRef(
    pingPongRef: Ref[IO, Map[ChatId, PingPong]],
    chatId: ChatId,
    update: PingPong => PingPong,
    initial: PingPong,
  ): IO[Unit] =
    pingPongRef.update(_.updatedWith(chatId)(_.map(update).getOrElse(initial).some))

  private def webSocketApp(
    wsb: WebSocketBuilder2[IO],
    userStatusManager: UserStatusManager[IO],
    chatHistoryRef: Ref[IO, Map[ChatId, ChatHistory]],
    redisPublisher: RedisPublisher[IO],
    pingPongRef: Ref[IO, Map[ChatId, PingPong]],
    chatTopicRef: Ref[IO, Map[ChatId, Topic[IO, Message]]],
  ): HttpApp[IO] = {
    val dsl = new Http4sDsl[IO] {}
    import dsl._
    CORS {
      HttpRoutes.of[IO] {
        case GET -> Root / "user" / "join" / username =>
          (generateRandomId, generateRandomId)
            .flatMapN { case (userId, chatId) =>
              for {
                _ <- IO.println(s"Registering $username in the system")
                _ <- chatHistoryRef
                  .update(_.updated(chatId, ChatHistory.init(username, userId, chatId)))
                  .flatTap(cache => IO.println(s"ChatHistory cache: $cache"))
                topic <- Topic[IO, Message]
                _ <- chatTopicRef
                  .update(_.updated(chatId, topic))
                  .flatTap(cache => IO.println(s"ChatTopics cache: $cache"))
                _ <- IO.println(s"Registered $username in the system")
                response <- Ok(Registered(userId, username, chatId).toJson)
              } yield response
            }
        case GET -> Root / "chat" / chatId =>
          val maybeChatHistory = findById(chatHistoryRef)(chatId)
          val maybeTopicF = findById(chatTopicRef)(chatId)

          val receiveF: IO[WebSocketFrameFlow] = maybeTopicF.map {
            case Some(topic) =>
              topic.publish.compose[WebSocketFrames](_.evalMap { case Text(body, _) =>
                body.as[ClientWsMsg] match {
                  case Left(error) => IO.println(s"could not deserialize $body: $error") as UnrecognizedMessage
                  case Right(clientWsMsg) =>
                    clientWsMsg.args match {
                      case userPong @ Pong(ChatParticipant.User) =>
                        IO.realTimeInstant.flatMap(now =>
                          updatePingPongRef(
                            pingPongRef = pingPongRef,
                            chatId = chatId,
                            update = _.copy(userTimeStamp = now.some),
                            initial = PingPong.initUser(now),
                          ) as userPong,
                        )
                      case supportPong @ Pong(ChatParticipant.Support) =>
                        IO.realTimeInstant.flatMap(now =>
                          updatePingPongRef(
                            pingPongRef = pingPongRef,
                            chatId = chatId,
                            update = _.copy(supportTimestamp = now.some),
                            initial = PingPong.initSupport(now),
                          ) as supportPong,
                        )
                      case Join(ChatParticipant.User, userId, username, None, None) =>
                        for {
                          _ <- IO.println(s"User with userId: $userId is attempting to join the chat server")
                          user = User(username, userId, chatId)
                          pubSubMsgAsJson = PubSubMessage.from(PubSubMessage.Args.UserPending(user)).toJson
                          _ <- IO.println(s"Writing $user into Redis SortedSet with Score 0 - pending")
                          _ <- userStatusManager.setPending(user.toJson)
                          _ <- IO.println(s"""Publishing $pubSubMsgAsJson into Redis pub/sub "users" channel""")
                          _ <- Stream.emit(pubSubMsgAsJson).through(redisPublisher.pipe).compile.drain
                        } yield UserJoined(user)
                      // User re-joins (browser refresh), so we load chat history
                      // TODO: consider storing chat history in the browser local storage
                      case Join(ChatParticipant.User, _, _, Some(_), _) =>
                        maybeChatHistory.map(_.getOrElse(ChatExpired(chatId)))
                      // Support joins for the first time
                      case Join(ChatParticipant.Support, userId, username, None, Some(su)) =>
                        for {
                          _ <- IO.println(s"Support attempting to join user with userId: $userId")
                          id <- generateRandomId
                          support = domain.Support(id, su, User(username, userId, chatId))
                          pubSubMsgAsJson = PubSubMessage.from(PubSubMessage.Args.SupportJoined(support)).toJson
                          _ <- Stream.emit(pubSubMsgAsJson).through(redisPublisher.pipe).compile.drain
                          _ <- IO.println(s"Support joined the user with userId: $userId")
                        } yield SupportJoined(support)
                      // Support re-joins (browser refresh), so we load chat history
                      // TODO: consider storing chat history in the browser local storage
                      case Join(ChatParticipant.Support, _, _, Some(_), Some(_)) =>
                        maybeChatHistory.map(_.getOrElse(ChatExpired(chatId)))
                      // chat message either from user or support
                      case msg: ChatMessage =>
                        for {
                          now <- IO.realTimeInstant
                          _ <- IO.println(s"participant:${msg.from} sent message:${msg.content} to:${msg.from.mirror} at:$now")
                          msgWithTimeStamp = msg.copy(timestamp = Some(now))
                          _ <- maybeChatHistory.flatMap {
                            case Some(messages) =>
                              chatHistoryRef
                                .update(_.updated(chatId, messages + msgWithTimeStamp))
                                .flatTap(cache => IO.println(s"ChatHistory cache: $cache"))
                                .as(msgWithTimeStamp)
                            case None => IO.pure(ChatExpired(chatId))
                          }
                        } yield msgWithTimeStamp
                    }
                }
              })
            case None => _ => Stream.empty
          }

          val sendF: IO[WebSocketFrames] = maybeTopicF.map {
            _.fold[WebSocketFrames](Stream.empty) {
              _.subscribe(10)
                .collect { case o: Out => Text(ServerWsMsg(o).toJson) }
                .merge {
                  // send "ping" message to WS client every 500 millis
                  val ping = Text("ping")
                  Stream.emit(ping) ++
                    Stream.awakeEvery[IO](500.millis) as ping
                }
            }
          }

          val receive: WebSocketFrames => Stream[IO, Unit] = (frames: WebSocketFrames) => Stream.eval(receiveF).flatMap(_(frames))
          val send: Stream[IO, WebSocketFrame] = Stream.eval(sendF).flatten

          wsb
            .withOnClose {
              (for {
                _ <- OptionT.liftF(Console[IO].println("WS connection was closed"))
                topic <- OptionT(maybeTopicF)
                _ <- OptionT.liftF(Temporal[IO].sleep(1500.millis))
                pingPong <- OptionT(pingPongRef.get.map(_.get(chatId)))
                _ <- OptionT.liftF {
                  WsConnectionClosedAction
                    .of[IO](chatHistoryRef, userStatusManager, redisPublisher)
                    .react(topic, chatId, pingPong)
                }
              } yield ()).value.void
            }
            .build(send, receive)
      }
    }
  }.orNotFound
}
