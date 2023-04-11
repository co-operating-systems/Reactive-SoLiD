/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp

import akka.actor.typed.*
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import run.cosy.ldp.fs.BasicContainer

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import run.cosy.ldp.DirTree
import run.cosy.ldp.ACLInfo.*

/** A Path to T Database that can be access and altered by multiple threads
  *
  * @tparam R the type of the Reference
  * @tparam A the type of the attributes
  */
trait PathDB[R, A]:

   import run.cosy.Solid.pathToList
   def hasAcl(a: A): Boolean

   /** todo: this should be a tree of paths so that if a branch higher up dies todo: then the whole
     * subtree can be pruned
     */
   val pathMap: AtomicReference[Option[DirTree[R, A]]] = new AtomicReference(None)

   /** add an actor Ref to the local DB */
   def addActorRef(path: Uri.Path, actorRef: R, optAttr: A): Unit =
     pathMap.updateAndGet { tree =>
        val pathLst = pathToList(path)
        tree match
         case None      => if (pathLst.isEmpty) then Some(DirTree(actorRef,optAttr)) else None
         case Some(ref) => Some(ref.insert(actorRef, pathLst, optAttr))
     }
     
   def setAttributeAt(path: Uri.Path, attr: A): Unit =
     pathMap.updateAndGet(_.map(_.replace(attr, pathToList(path))))

  /** remove an actor Ref from the local DB */
   def removePath(path: Uri.Path): Unit =
     pathMap.updateAndGet(_.flatMap(_.delete(pathToList(path))))

   /** get actorRef for a path
     *
     * @return
     *   Some(path,ref) where path is the remaining path to the actor, or None if there is none
     */
   def getActorRef(uriPath: Uri.Path): Option[(List[String], R, Option[R])] =
      val path = pathToList(uriPath)
      pathMap.getPlain.map(_.findClosestRs(path)(hasAcl))

end PathDB

import run.cosy.ldp.Messages.*

/** Whenever an LDPR actor goes up it should register itself here, so that messages can be routed
  * directly to the right actor, rather than passing the messages through the container hierarchy.
  * Implemented as an [[https://doc.akka.io/docs/akka/current/typed/extending.html Extension]].
  *
  * Note: One could limit this to LDPC Actors only, if LDPR Actors turn out to have too short a
  * life. There may also be one LDPR Actor per request, which would not work.
  *
  * todo: arguably, the only thing we need to store is if the container has an acl or not,
  *    so the type {{{PathDB[ActorRef[Messages.Route], Boolean]}}} would be sufficient. Finding
  *    the closest actor, always requires going through the path hierarchy, so that one always gets
  *    the last container with an acl. Having the MsgAcl stored as an attributed at each node, on the
  *    other hand means that if say a parent acl is deleted, then all the children in the Registry need
  *    to be changed too! Whereas with a boolean, we just need to change the registry that changes.
  *    This on the other hand would require the construction of new MsgACL object for every request
  *    (but that is quite minor, given the size of requests)
  *
  * @param system
  *   not used at present
  */
class ResourceRegistry(system: ActorSystem[?]) extends PathDB[ActorRef[Messages.Route], Boolean]
    with Extension:
  override def hasAcl(a: Boolean): Boolean = a

  import akka.http.scaladsl.model.Uri

object ResourceRegistry extends ExtensionId[ResourceRegistry]:
   def createExtension(system: ActorSystem[?]): ResourceRegistry =
     new ResourceRegistry(system)




