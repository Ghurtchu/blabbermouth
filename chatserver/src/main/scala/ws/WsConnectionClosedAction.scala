package ws

import cats.effect.kernel.Concurrent
import cats.effect.std.Console
import cats.effect.{Ref, Temporal}
import cats.{Applicative, Monad, Parallel}
import cats.syntax.all._
import fs2.concurrent.Topic
import json.Syntax.JsonWritesSyntax
import redis.PubSubMessage
import redis.PubSubMessage._
import users.UserStatusManager
import ws.Message.Out
import ws.Message.Out.ChatHistory

trait WsConnectionClosedAction[F[_]] {
  def react(topic: Topic[F, Message], chatId: String, pingPong: Option[PingPong]): F[Unit]
}

object WsConnectionClosedAction {

  def empty[F[_]: Applicative]: WsConnectionClosedAction[F] =
    (_, _, _) => ().pure[F]

  def of[F[_]: Monad: Console: Temporal: Concurrent: Parallel](
    chatHistory: Ref[F, Map[String, ChatHistory]],
    userStatusManager: UserStatusManager[F],
    redisPublisher: redis.RedisPublisher[F],
  ): WsConnectionClosedAction[F] = new WsConnectionClosedAction[F] {

    // TODO: This logic can be refined..
    // TODO: Draw diagrams with timestamps to understand all the cases
    def react(topic: Topic[F, Message], chatId: String, pingPong: Option[PingPong]): F[Unit] =
      pingPong match {
        // both of them were joined at some point
        case Some(PingPong(Some(userT), Some(supportT))) =>
          if (userT.isBefore(supportT))
            userLeftChat(topic, chatId, chatHistory, userStatusManager, redisPublisher)
          else
            Console[F].println("Support left the chat") *>
              topic.publish1(Out.SupportLeft(chatId)).void

        case _ => userLeftChat(topic, chatId, chatHistory, userStatusManager, redisPublisher)
//        // only user has joined, so if WS get closed it means that only user has left :)
//        case Some(PingPong(Some(_), None)) =>
//          userLeftChat(topic, chatId, chatHistory, userStatusManager, redisPublisher)
//
//        // if PingPong is None it means that User hasn't even sent one "pong" response to WS and immediately left
//        case None =>
//          userLeftChat(topic, chatId, chatHistory, userStatusManager, redisPublisher)
//
//        // in any other cases it's only possible that User leaves the chat, not the Support
//        case _ => userLeftChat(topic, chatId, chatHistory, userStatusManager, redisPublisher)
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
