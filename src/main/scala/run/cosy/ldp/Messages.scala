/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp

import akka.actor.typed.ActorRef
import akka.http.scaladsl.model.StatusCodes.*
import akka.http.scaladsl.model.headers.*
import akka.http.scaladsl.model.*
import akka.stream.Materializer
import run.cosy.http.auth.Agent
import run.cosy.ldp.SolidCmd
import run.cosy.ldp.SolidCmd.{Get, Meta, Plain, ReqDataSet, Response, Script, Wait}
import scalaz.{ICons, INil, NonEmptyList}
import scalaz.NonEmptyList.nel
import run.cosy.ldp.ResourceRegistry

import java.nio.file.Path
import scala.concurrent.ExecutionContext
import scala.util.Failure

object Messages:
   sealed trait Cmd

   // trait for a Cmd that is to be acted upon
   sealed trait Act extends Cmd:
      def msg: CmdMessage[?]
   sealed trait Route extends Cmd:
      def msg: CmdMessage[?]
   sealed trait Info extends Cmd

   // todo: ask for the whole URL not just the path, so that we can also be guided to proxy actor.
   private def send[A](msg: CmdMessage[A], uriPath: Uri.Path)(using
       reg: ResourceRegistry
   ): Option[Unit] =
     reg.getActorRef(uriPath).map { (remaining, sendTo) =>
       sendTo ! {
         remaining match
            case Nil          => WannaDo(msg)
            case head :: tail => RouteMsg(NonEmptyList.fromSeq(head, tail.toSeq), msg)
       }
     }

   /** CmdMessage wraps an LDPCmd that is part of a Free Monad Stack. Ie. the `command` is the
     * result of an LDPCmd.Script[T].resume todo: use
     * [[https://dotty.epfl.ch/docs/reference/other-new-features/type-test.html Dotty TypeTest]] to
     * work with generics?
     */
   case class CmdMessage[R](
       commands: SolidCmd[Script[R]],
       from: Agent,
       replyTo: ActorRef[R]
   )(using
       val reg: ResourceRegistry,
       val mat: Materializer,
       val ec: ExecutionContext,
       val fscm: cats.Functor[SolidCmd]
   ):
      def target: Uri = commands.url

      def continue(x: Script[R]): Unit = x.resume match
         case Right(answer) => replyTo ! answer
         case Left(next) =>
           val c: CmdMessage[R] = CmdMessage[R](next, from, replyTo)
           send(c, next.url.path)

      def respondWithScr(res: HttpResponse): ScriptMsg[R] =
        commands match
           case Plain(req, k) => ScriptMsg(k(res), from, replyTo)
           case g @ Get(u, k) =>
             val x: Script[R] = k(Response(
               Meta(g.url, res.status, res.headers),
               Failure(Exception(
                 "what should I put here? We can only put a failure I think because the " +
                   "response to a Get should be a Graph. So unless this is a serialised graph..."
               ))
             ))
             ScriptMsg(x, from, replyTo)
           case Wait(f, u, k) => ??? // not sure what to do here!

           /** If this is a Plain Http Request then all responses can go through here. Otherwise,
             * this actually indicates an error, since the constructed object is not returned. This
             * smells like we have a type problem here
             */
      def respondWith(res: HttpResponse): Unit =
        commands match
           case Plain(req, k) => continue(k(res))
           case g @ Get(u, k) =>
             val x: Script[R] = k(Response(
               Meta(g.url, res.status, res.headers),
               Failure(Exception("what should I put here?"))
             ))
             continue(x)
           case Wait(f, u, k) => ??? // not sure what to do here!

      /** Here we redirect the message to the new resource. BUT! todo: should the redirect be
        * implemented automatically, or be something for the monad construction layer? The latter
        * would be a lot more flexible, and would also work better with remote resources over a
        * proxy.
        */
      def redirectTo(to: Uri, msg: String): Unit = respondWith(
        HttpResponse(StatusCodes.MovedPermanently, Seq(Location(to)))
      )

   /** ScriptMsg don't yet know where they want to go, as that requires the script to be first
     * `continue`ed, in order to find the target. Note: calling continue on the script can result in
     * an answer to be returned to the replyTo destination!
     */
   case class ScriptMsg[R](
       script: Script[R],
       from: Agent,
       replyTo: ActorRef[R]
   )(using
       val reg: ResourceRegistry,
       val mat: Materializer,
       val ec: ExecutionContext,
       val fscm: cats.Functor[SolidCmd]
   ) extends Cmd:
      // todo: this code duplicates method from CmdMessage
      def continue: Unit = script.resume match
         case Right(answer) => replyTo ! answer
         case Left(next) =>
           val c: CmdMessage[R] = CmdMessage[R](next, from, replyTo)
           send(c, next.url.path)

   /** @param remainingPath
     *   the path to the final resource
     */
   final case class RouteMsg(remainingPath: NonEmptyList[String], msg: CmdMessage[?]) extends Route:
      def nextSegment: String = remainingPath.head

      // check that path is not empty before calling  (anti-pattern)
      def nextRoute: Route = remainingPath match
         case NonEmptyList(_, INil())         => WannaDo(msg)
         case NonEmptyList(_, ICons(h, tail)) => RouteMsg(nel(h, tail), msg)

   /** a command to be executed on the resource on which it arrives, after being authorized */
   final case class Do(msg: CmdMessage[?]) extends Act with Route

   /** message has arrived, but still needs to be authorized */
   final case class WannaDo(msg: CmdMessage[?]) extends Route:
      def toDo = Do(msg)

   // responses from child to parent
   case class ChildTerminated(name: String) extends Info
