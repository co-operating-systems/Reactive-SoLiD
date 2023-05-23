/** Copyright 2021 Henry Story
  *
  * SPDX-License-Identifier: Apache-2.0
  */

package run.cosy.ldp

import akka.actor.ActorPath
import akka.actor.typed.ActorRef
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import run.cosy.http.util.UriX.*
import run.cosy.ldp.ACInfo.{ACContainer, ACtrlRef}
import run.cosy.ldp.Messages.{Route, ScriptMsg, WannaDo}
import run.cosy.ldp.fs.Resource.AcceptMsg

import scala.annotation.tailrec

/** Access Control Info types */

/** see [../README](../README.md). Avery ACInfo gives us the acl Uri Path. But we can either know
  * the actor of the container or of the AC Resource itself, hence two subtypes
  *
  * @param container
  *   the URI of the container in which the ACL is stored. (the container may be a resource, since
  *   resources can have a number of representations (and so contain those).
  */
enum ACInfo(val container: Uri):
   // todo: this should not be needed, use ACContainer instead
   // mhh, could also be useful for links from acl files to themselves.
   // todo: in which case find a better name
   case Root(containerUrl: Uri) extends ACInfo(containerUrl)
   // todo: container type should be ActorRef[Route|ScriptMsg[Boolean]] so that we can send acl scripts directly
   /** The ACContainer actor is not the ACL actor, but its parent */
   case ACContainer(containerUrl: Uri, ref: ActorRef[Route]) extends ACInfo(containerUrl)

   /** The ACRef actor is the one containing the ACR graph */
   case ACtrlRef(
       forContainer: Uri,
       acName: String,
       actor: ActorRef[AcceptMsg],
       isContainerAcl: Boolean
   ) extends ACInfo(forContainer)

   // todo: different directories may want their own naming conventions for Urls, so we should pass
   //  the acl name around too. But for now, let's calculate it
   def aclUri: Uri = this match
    case ACtrlRef(container, acName, _, isContainer) =>
      if isContainer then container ?/ acName
      else container.sibling(acName + ".acl")
    case other => other.container ?/ ".acl"

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
      def toUriPath(starting: Int=0): Path =
        path.elements.drop(starting).foldLeft(Uri.Path./)((p, name) => p ?/ name)

end ACInfo
