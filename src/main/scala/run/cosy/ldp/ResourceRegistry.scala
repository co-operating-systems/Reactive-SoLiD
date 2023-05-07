/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp

import akka.actor.typed.*
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import run.cosy.Solid.pathToList
import run.cosy.ldp.fs.BasicContainer

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import run.cosy.ldp.DirTree
import run.cosy.ldp.ACInfo
import run.cosy.ldp.ACInfo.ACContainer

import run.cosy.ldp.Messages.*

/** Whenever an LDPR actor goes up it should register itself here, so that messages can be routed
  * directly to the right actor, rather than passing the messages through the container hierarchy.
  * See [[ResourceRegistry.md ResourceRegistry]]
  *
  * Implemented as an [[https://doc.akka.io/docs/akka/current/typed/extending.html Extension]].
  *
  * Note: One could limit this to LDPC Actors only, if LDPR Actors turn out to have too short a
  * life. There may also be one LDPR Actor per request, which would not work.
  *
  * todo: arguably, the only thing we need to store is if the container has an acl or not, so the
  * type {{{PathDB[ActorRef[Messages.Route], Boolean]}}} would be sufficient. Finding the closest
  * actor, always requires going through the path hierarchy, so that one always gets the last
  * container with an acl. Having the MsgAcl stored as an attributed at each node, on the other hand
  * means that if say a parent acl is deleted, then all the children in the Registry need to be
  * changed too! Whereas with a boolean, we just need to change the registry that changes. This on
  * the other hand would require the construction of new MsgACL object for every request (but that
  * is quite minor, given the size of requests)
  */
class ResourceRegistry(rootUri: Uri, rootLDPC: ActorRef[Messages.Route])
    extends PathDB[ActorRef[Messages.Route], Boolean]:

   override def hasAcl(a: Boolean): Boolean = a
   val rootACUri                            = rootUri.copy(path = rootUri.path ?/ ".acl")

   // todo: remove the Option, requires knowing the root ActorRef
   /** We map the information in the DB to what is more useable. todo: Later if this works we can
     * improve the data structure so that no more calculations are needed.
     */
   def getActorRefAndAC(uriPath: Uri.Path): (List[String], ActorRef[Messages.Route], ACInfo) =
      val path = pathToList(uriPath)
      import ACInfo.*
      getActorRef(path).map { (lst, containerRef, lastACContainerRefOpt) =>
         val ac: ACInfo = lastACContainerRefOpt match
          case None => // todo: it would be nice if we could get rid of this
            Root(rootACUri)
          case Some(lastAC) => // todo: replace 2 below with calcualted value
            val acr = lastAC.path.toUri(2) ?/ ".acl"
            ACContainer(rootUri.copy(path = acr), lastAC)
         (lst, containerRef, ac)
      }.getOrElse((path, rootLDPC, Root(rootACUri)))
