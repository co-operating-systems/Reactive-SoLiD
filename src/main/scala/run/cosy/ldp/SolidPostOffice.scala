/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp

import akka.actor.typed.*
import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import run.cosy.ldp.Messages.{ActorRT, CmdMessage, MsgPath, Route, RouteMsg, WannaDo}
import cats.data.NonEmptyList
import run.cosy.http.auth.Agent
import run.cosy.ldp.SolidCmd.Script

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.concurrent.Future

/** passing the actorSystem will allow the post office to create actors for new remote services
  * perhaps and also start an actor for error messages.
  */
class SolidPostOffice(system: ActorSystem[?]) extends Extension:
   type Ref  = ActorRef[Messages.Route]
   type Attr = Boolean

   /** to start very simple we start with only allowing domain roots. todo: later add data
     * structures to have paths higher on a server. todo: also wrap in a more secure structure later
     */
   val roots: AtomicReference[Map[Uri.Authority, ResourceRegistry]] = new AtomicReference(Map())

   /** find the actor for the given command and sent it there or send an error back */
   def send[R](msg: CmdMessage[R]): Unit =
     roots.get().get(msg.commands.url.authority) match
      case None =>
        // todo: then we send it over the web or to the web cache.
        ???
      case Some(reg) =>
        // taken from Messages.send(..)
        val (remaining: MsgPath, sendTo: ActorRT, defltAcl: ACInfo) =
          reg.getActorRefAndAC(msg.commands.url.path)
        sendTo ! {
          remaining match
           case Nil          => WannaDo(msg, defltAcl)
           case head :: tail => RouteMsg(NonEmptyList(head, tail), msg, defltAcl)
        }
   end send

   def ask[Res](cmd: SolidCmd[Script[Res]], from: Agent)(using
       timeout: Timeout,
       scheduler: Scheduler
   ): Future[Res] =
      import akka.actor.typed.scaladsl.AskPattern.*
      roots.get().get(cmd.url.authority) match
       case None =>
         // todo: then we send it over the web or to the web cache.
         ???
       case Some(reg) =>
         // taken from Messages.send(..)
         val (remaining: MsgPath, sendTo: ActorRT, defltAcl: ACInfo) =
           reg.getActorRefAndAC(cmd.url.path)
         def f(returnTo: ActorRef[Res]): Route =
            val msg = CmdMessage[Res](cmd, from, returnTo)
            remaining match
             case Nil          => WannaDo(msg, defltAcl)
             case head :: tail => RouteMsg(NonEmptyList(head, tail), msg, defltAcl)
         sendTo.ask(f)

   def addRoot(uri: Uri, actorRef: Ref): Unit =
     roots.getAndUpdate(_ + (uri.authority -> ResourceRegistry(uri, actorRef)))

   def addActorRef(uri: Uri, actorRef: Ref, optAttr: Attr): Unit =
     roots.get().get(uri.authority) match
      case Some(reg) =>
        reg.addActorRef(uri.path, actorRef, optAttr)
      case None => system.log.warn(
          s"failed adding actorRef $actorRef at $uri "
        )

   /** remove the path at the given Resource Registry.
     *
     * todo: also remove the resource registry itself if the root is removed...
     */
   def removePath(uri: Uri): Unit =
     roots.get().get(uri.authority) match
      case Some(reg) =>
        reg.removePath(uri.path)
      case None => system.log.warn(
          s"failed find registry for path $uri"
        )

object SolidPostOffice extends ExtensionId[SolidPostOffice]:
   override def createExtension(system: ActorSystem[?]): SolidPostOffice =
     new SolidPostOffice(system)
