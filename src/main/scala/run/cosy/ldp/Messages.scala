/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp

import akka.actor.typed.ActorRef
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.StatusCodes.*
import akka.http.scaladsl.model.headers.*
import akka.stream.Materializer
import cats.data.NonEmptyList
import run.cosy.http.auth.Agent
import run.cosy.ldp.ACInfo.*
import run.cosy.ldp.SolidCmd.{Get, Meta, Plain, ReqDataSet, Response, Script, Wait}
import run.cosy.ldp.fs.BasicContainer
import run.cosy.ldp.{ResourceRegistry, SolidCmd}

import java.nio.file.Path
import scala.concurrent.ExecutionContext
import scala.util.Failure

object Messages:
   type MsgPath = List[String] // message path
   type ActorRT = ActorRef[Messages.Route]

   sealed trait Cmd
   // trait for a Cmd that is to be acted upon
   sealed trait Act extends Cmd:
      def msg: CmdMessage[?]
   sealed trait Route extends Cmd:
      def msg: CmdMessage[?]
   sealed trait Info extends Cmd

   /** CmdMessage wraps an LDPCmd that is part of a Free Monad Stack. Ie. the `command` is the
     * result of an LDPCmd.Script[T].resume todo: use
     * [[https://dotty.epfl.ch/docs/reference/other-new-features/type-test.html Dotty TypeTest]] to
     * work with generics?
     */
   case class CmdMessage[R](
       commands: SolidCmd[Script[R]],
       from: Agent,
       replyTo: ActorRef[R]
   )(using val fscm: cats.Functor[SolidCmd]):
      def target: Uri = commands.url

      def continue(x: Script[R])(using po: SolidPostOffice): Unit = x.resume match
       case Right(answer) => replyTo ! answer
       case Left(next)    => po.send(CmdMessage[R](next, from, replyTo))

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

         /** If this is a Plain Http Request then all responses can go through here. Otherwise, this
           * actually indicates an error, since the constructed object is not returned. This smells
           * like we have a type problem here
           */
      def respondWith(res: HttpResponse)(using SolidPostOffice): Unit =
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
      def redirectTo(to: Uri, msg: String)(using SolidPostOffice): Unit = respondWith(
        HttpResponse(StatusCodes.MovedPermanently, Seq(Location(to)))
      )

   /** ScriptMsg don't yet know where they want to go, as that requires the script to be first
     * `continue`d, in order to find the target. Note: calling continue on the script can result in
     * an answer to be returned to the replyTo destination!
     */
   case class ScriptMsg[R](
       script: Script[R],
       from: Agent,
       replyTo: ActorRef[R]
   )(using val fscm: cats.Functor[SolidCmd]) extends Cmd:
      // todo: this code duplicates method from CmdMessage
      def continue(using po: SolidPostOffice): Unit = script.resume match
       case Right(answer) => replyTo ! answer
       case Left(next)    => po.send(CmdMessage[R](next, from, replyTo))

   /** @param remainingPath
     *   the path to the final resource
     * @param lastSeenAC
     *   provides a reference to the nearest container actor that has an AC resource. As the message
     *   is routed through the hierarchy this will pickup whatever the last container states is the
     *   last container acl. If a container no longer knows because its AC resource has been
     *   deleted, then such a message can be used by the container to set its value. The value can
     *   be None, if the message has not seen a container. We use actors to speed up sending
     *   messages and since if a supervisor actor dies all its children die, it is always ok for
     *   children to send messages directly to their parents.
     */
   final case class RouteMsg(
       remainingPath: NonEmptyList[String],
       msg: CmdMessage[?],
       lastSeenAC: ACInfo
   ) extends Route:
      def nextSegment: String = remainingPath.head

      // check that path is not empty before calling  (anti-pattern)
      /** @param localAclInfo
        *   this is the aclInfo for the message given the information known by the caller
        */
      def nextRoute(localAclInfo: ACInfo): Route =
        remainingPath match
         case NonEmptyList(_, Nil) => WannaDo(msg, localAclInfo)
         case NonEmptyList(_, h :: tail) =>
           RouteMsg(NonEmptyList(h, tail), msg, localAclInfo)

   /** a command to be executed on the resource on which it arrives, after being authorized */
   final case class Do(msg: CmdMessage[?]) extends Act with Route

   /** message has arrived, but still needs to be authorized. Note: a Wannado can come directly from
     * a request to the registry. see `def send` in Messages The lastSeenAC should be used for
     * authorization rules if final resource does not have its own existing acl.
     */
   final case class WannaDo(
       msg: CmdMessage[?],
       lastSeenAC: ACInfo
   ) extends Route:
      def toDo = Do(msg)

   // responses from child to parent
   case class ChildTerminated(name: String) extends Info
