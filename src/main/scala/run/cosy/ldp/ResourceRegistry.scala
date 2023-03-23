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
import scalaz.NonEmptyList

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable.HashMap

/** A Path to T Database that can be access and altered by multiple threads
  *
  * @tparam T
  */
trait PathDB[T]:

   import run.cosy.Solid.pathToList

   /** todo: this should be a tree of paths so that if a branch higher up dies todo: then the whole
     * subtree can be pruned
     */
   val pathMap: AtomicReference[Option[ATree[T]]] = new AtomicReference(None)

   /** add an actor Ref to the local DB */
   def addActorRef(path: Uri.Path, actorRef: T): Unit =
     pathMap.updateAndGet { tree =>
        val pathLst = pathToList(path)
        tree match
         case None      => if (pathLst.isEmpty) then Some(ATree(actorRef)) else None
         case Some(ref) => Some(ref.insert(actorRef, pathLst))
     }

   /** remove an actor Ref from the local DB */
   def removePath(path: Uri.Path): Unit =
     pathMap.updateAndGet(_.flatMap(_.delete(pathToList(path))))

   /** get actorRef for a path
     *
     * @return
     *   Some(path,ref) where path is the remaining path to the actor, or None if there is none
     */
   def getActorRef(uriPath: Uri.Path): Option[(List[String], T)] =
      val path = pathToList(uriPath)
      pathMap.getPlain.map(_.findClosest(path))

end PathDB

import run.cosy.ldp.Messages.*

/** Whenever an LDPR actor goes up it should register itself here, so that messages can be routed
  * directly to the right actor, rather than passing the messages through the container hierarchy.
  * Implemented as an [[https://doc.akka.io/docs/akka/current/typed/extending.html Extension]].
  *
  * Note: One could limit this to LDPC Actors only, if LDPR Actors turn out to have too short a
  * life. There may also be one LDPR Actor per request, which would not work.
  *
  * @param system
  *   not used at present
  */
class ResourceRegistry(system: ActorSystem[?]) extends PathDB[ActorRef[Messages.Route]]
    with Extension:
   import akka.http.scaladsl.model.Uri

object ResourceRegistry extends ExtensionId[ResourceRegistry]:
   def createExtension(system: ActorSystem[?]): ResourceRegistry =
     new ResourceRegistry(system)

/** Immutable tree structure to keep path to references could perhaps use cats.free.Cofree. A Tree
  * is very likely a comonad. (which would make coMonad functions available - would they be useful?)
  */
case class ATree[A](a: A, kids: HashMap[String, ATree[A]] = HashMap()):
   type Path = List[String]

   /** Insert a new A at given path, and ignore any existing subtrees at that position. This makes
     * sense for our main use case of ActorRef, since changing an actorRef would change all the
     * subtree
     */
   final def insert(newRef: A, at: Path): ATree[A] =
     at match
      case Nil => if a == newRef then this else ATree[A](newRef)
      // note here we loose any subtrees below the insertion.
      case name :: Nil  => ATree(a, kids + (name -> ATree[A](newRef)))
      case name :: tail =>
        // todo: stack problem. Need tail recursive version of this
        val alt = kids.get(name).map(kid => kid.insert(newRef, tail))
        // if we don't have a child, but we have to add a grandchild, then what should we put
        // as the value of the child? (it could happen I guess that some message of the grandchild arrives faster?)
        // here we ignore everything... (it is better for the message to work its way up the hierarchy)
        // todo: if that is the case, messages that go up the hierarchy too early should be a signal to the
        //   actors to try to re-register
        alt match
         case None    => this // we could not insert anything so we return this
         case Some(k) => ATree(a, kids + (name -> k))

   /** we need to replace the A at at path, but keep everything else the same. This happens when a
     * property of the ref changes, but not the tree structure, eg. if we keep the same ActorRef but
     * change the info about acls
     */
   final def replace(newRef: A, at: Path): ATree[A] =
     at match
      case Nil => if a == newRef then this else ATree[A](newRef, kids)
      // note here we loose any subtrees below the insertion.
      case name :: Nil =>
        kids.get(name) match
         case None       => ATree(a, kids + (name -> ATree[A](newRef)))
         case Some(tree) => ATree(a, kids + (name -> ATree[A](newRef, tree.kids)))
      case name :: tail =>
        // todo: stack problem. Need tail recursive version of this
        val alt = kids.get(name).map(kid => kid.insert(newRef, tail))
        alt match
         case None    => this // we could not insert anything so we return this
         case Some(k) => ATree(a, kids + (name -> k))

   final def delete(at: Path): Option[ATree[A]] =
     at match
      case Nil => None
      case name :: tail => // todo: stack problem
        Some(deleteNE(name, tail))

   final def deleteNE(name: String, remaining: List[String]): ATree[A] =
     kids.get(name) match
      case None => this
      case Some(tree) =>
        remaining match
         case Nil => ATree(a, kids - name)
         case head :: tail => // todo: stack problem
           val alt = tree.deleteNE(head, tail)
           ATree(a, kids + (name -> alt))

   /** @param at
     *   path to resource A
     * @return
     *   a pair of the remaining path and A
     */
   final def findClosest(at: Path): (Path, A) =
     at match
      case Nil => (at, a)
      case name :: tail => // todo: stack problem
        kids.get(name) match
         case None => (at, a)
         case Some(tree) =>
           tree.findClosest(tail)

end ATree
