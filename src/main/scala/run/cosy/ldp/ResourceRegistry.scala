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

/** import cats.free.Cofree could perhaps use Cofree see
  * [[https://gitter.im/typelevel/cats?at=602ed9f39337c51bc6962f1b discussion on cats gitter]] Only
  * use in synchronised block
  */
case class ATree[A](a: A, kids: HashMap[String, ATree[A]] = HashMap[String, ATree[A]]()):
   type Path = List[String]

   final def insert(newRef: A, at: Path): ATree[A] =
     at match
        case Nil         => if a == newRef then this else ATree[A](newRef)
        case name :: Nil => ATree(a, kids + (name -> ATree[A](newRef)))
        case name :: tail => // todo: stack problem
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
