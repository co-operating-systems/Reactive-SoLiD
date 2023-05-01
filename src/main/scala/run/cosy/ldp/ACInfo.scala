/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp

import akka.actor.ActorPath
import akka.actor.typed.ActorRef
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import run.cosy.ldp.Messages.{Route, ScriptMsg, WannaDo}
import run.cosy.ldp.fs.Resource.AcceptMsg

import scala.annotation.tailrec

/** Access Control Info types */

/** see [../README](../README.md). Avery ACInfo gives us the acl Uri Path. But we can either know
  * the actor of the container or of the AC Resource itself, hence two subtypes
  */
enum ACInfo(val acl: Uri):
   // todo: this should not be needed, use ACContainer instead
   // mhh, could also be useful for links from acl files to themselves.
   // todo: in which case find a better name
   case Root(acu: Uri) extends ACInfo(acu)
   // todo: container type should be ActorRef[Route|ScriptMsg[Boolean]] so that we can send acl scripts directly
   /** The ACContainer actor is not the ACL actor, but its parent */
   case ACContainer(acu: Uri, container: ActorRef[Route]) extends ACInfo(acu)

   /** The ACRef actor is the one containing the ACR graph */
   case ACtrlRef(acu: Uri, actor: ActorRef[AcceptMsg]) extends ACInfo(acu)

object ACInfo:
   extension (path: Uri.Path)
     def toActorPath(root: ActorPath): ActorPath =
        @tailrec def rec(path: Path, constr: ActorPath): ActorPath = path match
         case Path.Empty               => constr
         case Path.Slash(Path.Empty)   => constr
         case Path.Slash(tail)         => rec(tail, constr)
         case Path.Segment(head, tail) => rec(tail, constr / head)
        rec(path, root)

   extension (path: ActorPath)
      def toUri: Uri.Path =
        path.elements.foldLeft(Uri.Path./)((p, name) => p / name)

      def toUri(starting: Int) =
        path.elements.drop(starting).foldLeft(Uri.Path./)((p, name) => p ?/ name)

end ACInfo
