package connection

import cats.effect.kernel.Concurrent
import cats.effect.std.Console
import cats.effect.{Ref, Temporal}
import cats.syntax.all._
import cats.{Applicative, Monad, Parallel}
import domain.ChatParticipant
import fs2.concurrent.Topic
import json.Syntax.JsonWritesSyntax
import redis.PubSubMessage
import redis.PubSubMessage._
import users.UserStatusManager
import ws.Message.Out.ChatHistory
import ws.Message.{Out, SupportLeft}
import ws.Message

trait WebSocketConnectionClosedAction[F[_]] {
  def react(topic: Topic[F, Message], chatId: String, pingPong: PingPong): F[Unit]
}

object WebSocketConnectionClosedAction {

  def empty[F[_]: Applicative]: WebSocketConnectionClosedAction[F] =
    (_, _, _) => ().pure[F]

  def of[F[_]: Monad: Console: Temporal: Concurrent: Parallel](
    chatHistory: Ref[F, Map[String, ChatHistory]],
    userStatusManager: UserStatusManager[F],
    redisPublisher: redis.RedisPublisher[F],
    pingPongRef: Ref[F, Map[String, PingPong]],
  ): WebSocketConnectionClosedAction[F] = new WebSocketConnectionClosedAction[F] {
    def react(topic: Topic[F, Message], chatId: String, pingPong: PingPong): F[Unit] =
      WebSocketClosureParticipantIdentifier.from(pingPong) match {
        case Some(ChatParticipant.User) =>
          for {
            _ <- userLeftChat(topic, chatId, chatHistory, userStatusManager, redisPublisher)
            _ <- pingPongRef.update {
              _.updatedWith(chatId) {
                case Some(pingPong) => Some(pingPong.dropUser)
                case None           => None
              }
            }
          } yield ()
        case Some(ChatParticipant.Support) =>
          for {
            _ <- Console[F].println("Support left the chat")
            _ <- topic.publish1(SupportLeft(chatId)).void
            _ <- pingPongRef.update {
              _.updatedWith(chatId) {
                case Some(pingPong) => Some(pingPong.dropSupport)
                case None           => None
              }
            }
          } yield ()
        // maybe add a warning or debug to indicate that such a rare scenario might occur
        case None => ().pure[F]
      }
  }

  private def userLeftChat[F[_]: Monad: Console: Concurrent: Parallel](
    topic: Topic[F, Message],
    chatId: String,
    chatHistory: Ref[F, Map[String, ChatHistory]],
    userStatusManager: UserStatusManager[F],
    redisPublisher: redis.RedisPublisher[F],
  ): F[Unit] = for {
    _ <- Console[F].println("User left the chat")
    maybeChatHistory <- chatHistory.get.map(_.get(chatId))
    _ <- maybeChatHistory.traverse_ { case ChatHistory(user: domain.User, _) =>
      (
        Console[F].println(s"setting status to inactive for user: ${user.username}, userId: ${user.userId}") *>
          userStatusManager.setInactive(user.toJson),
        Console[F].println("sending UserLeft message to client") *>
          topic.publish1(Out.UserLeft(chatId)).void,
        Console[F].println("publishing UserLeft message to Redis Pub/Sub") *> fs2.Stream
          .emit(PubSubMessage.from(PubSubMessage.Args.UserLeft(chatId)).toJson)
          .through(redisPublisher.pipe)
          .compile
          .drain,
      ).parTupled.void
    }
  } yield ()
}
