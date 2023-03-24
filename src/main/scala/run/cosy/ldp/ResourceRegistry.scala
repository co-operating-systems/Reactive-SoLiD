/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp

import akka.actor.typed.*
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import cats.data.NonEmptyList
import run.cosy.ldp.fs.BasicContainer

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
   val pathMap: AtomicReference[Option[DirTree[T]]] = new AtomicReference(None)

   /** add an actor Ref to the local DB */
   def addActorRef(path: Uri.Path, actorRef: T): Unit =
     pathMap.updateAndGet { tree =>
        val pathLst = pathToList(path)
        tree match
         case None      => if (pathLst.isEmpty) then Some(DirTree(actorRef)) else None
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

/** Immutable Rose tree structure to keep path to references could perhaps use
  * {{{
  * type DirTree[A] = cats.free.Cofree[HashMap[String,_],A]
  * }}}
  * see:
  * [[https://app.gitter.im/#/room/#typelevel_cats:gitter.im/$jOKyOT_G008b4VZxjdqvfojzyK-q4xaLykQMsg6HJ20 Feb 2021 discussion on gitter]].
  * Especially this talk by @tpolecat
  * [[http://tpolecat.github.io/presentations/cofree/slides#1 Fun & Games with Fix, Cofree, and Doobie!]].
  * It is not clear though exactly what advantage the Cofree library gives one though for this
  * project. Does the library give one ways of making changes into the structure?
  * ([[https://www.youtube.com/watch?v=7xSfLPD6tiQ video of the talk]])
  *
  * To make changes the data structure one could use Lenses. I actually have a very close example
  * where I was modelling a web server and making changes to it using Monocle.
  * [[https://github.com/bblfish/lens-play/blob/master/src/main/scala/server/Server.scala lens-play Server.scala]]
  * Here we only need to model the Containers so it simplifies a lot.
  *
  * Otherwise in order to make the transformations tail recursive one has to use a Zipper to build a
  * Path to the changed node and then reconstruct the tree with that as explained in this
  * [[https://stackoverflow.com/questions/17511433/scala-tree-insert-tail-recursion-with-complex-structure Stack Overflow Answer]]
  *
  * Would one reason to use Cofree be that one could use double tail recursion, and that could be
  * more efficient than the Path deconstruction and data reconstruction route?
  * [[https://stackoverflow.com/questions/55042834/how-to-make-tree-mapping-tail-recursive stack overflow answer]]
  * One of those posts pointed out that using tail recursion slows things down by a factor of 4, and
  * speed should be top priority here.
  *
  * So really we should either use a lens lib or implement it ourselves. Here we choose to reduce
  * dependencies.
  */
object DirTree:
  // a path of directory names, starting from the root going inwards
  // todo: could one also use directly a construct from Akka actor path?
  type Path = List[String]

  /** A Path of DirTree[A]s is used to take apart a DirTree[A] structure, in the reverse direction.
    * Note the idea is that each link (name, dt) points from dt via name in the hashMap to the next
    * deeper node. The APath is in reverse direction so we have List( "newName" <- dir2 , "dir2in1"
    * <- dir1 , "dir1" <- rootDir ) An empty List would just refer to the root DirTree[A]
    */
  type ALink[A] = (String, DirTree[A])
  type APath[A] = List[ALink[A]]
  // the first projection is the remaining path
  type SearchAPath[A] = (Path, APath[A])

  extension [A](dt: DirTree[A])
     /** @param at path to resource A
       * @return
       * a pair of the remaining path and A
       */
     @tailrec
     def findClosest(at: Path): (Path, A) =
       at match
         case Nil => (at, dt.a)
         case name :: tail =>
           dt.kids.get(name) match
             case None => (at, dt.a)
             case Some(tree) =>
               tree.findClosest(tail) //this should be recursive!

     /** note we can only find the closest path to something if the path is not empty */
     def toClosestAPath(path: Path): SearchAPath[A] =
       @tailrec
       def loop(dt: DirTree[A], path: Path, result: APath[A]): SearchAPath[A] =
         path match
           case Nil => (Nil, result)
           case name :: rest =>
             if dt.kids.isEmpty then (rest, (name, dt) :: result)
             else
               dt.kids.get(name) match
                 case None => (rest, (name, dt) :: result)
                 case Some(dtchild) =>
                   loop(dtchild, rest, (name, dt) :: result)
       end loop
       loop(dt, path, Nil)
     end toClosestAPath

  
//     @tailrec
//     def deleteNE(name: String, remaining: List[String]): DirTree[A] =
//       dt.kids.get(name) match
//         case None => dt
//         case Some(tree) =>
//           remaining match
//             case Nil => DirTree(dt.a, dt.kids - name)
//             case head :: tail =>
//               val alt = tree.deleteNE(head, tail) //
//               DirTree(dt.a, dt.kids + (name -> alt))
//

end DirTree

/** Another option could have been
  * {{{
  * import cats.free.Cofree
  *
  * type HMap[X] = HashMap[String,X]
  * type DirTree[A] = Cofree[HMap[_],A]
  * }}}
  */
case class DirTree[A](a: A, kids: HashMap[String, DirTree[A]] = HashMap()):
   import DirTree.*

   /** Insert a new A at given path, and ignore any existing subtrees at that position. This makes
     * sense for our main use case of ActorRef, since changing an actorRef would change all the
     * subtree
     */
   final def insert(newRef: A, at: Path): DirTree[A] =
     place(at){ _ => new DirTree(newRef)} // we forget all subtrees of dt

   final def place(at: Path)(f: DirTree[A] => DirTree[A]): DirTree[A] =
      @tailrec
      def loop(path: APath[A], result: DirTree[A]): DirTree[A] =
        path match
         case Nil                 => result
         case (name, obj) :: tail => loop(tail, DirTree(obj.a, obj.kids + (name -> result)))

       // note here we loose any subtrees below the insertion.
      this.toClosestAPath(at) match
          case (Nil, Nil) => f(this)
          case (Nil, apath) => loop(apath, f(apath.head._2))
          // something remains, so we can't insert. We stay where we are
          case other => this
   end place
   

   /** we need to replace the A at at path, but keep everything else the same. This happens when a
     * property of the ref changes, but not the tree structure, eg. if we keep the same ActorRef but
     * change the info about acls
     */
   final def replace(newRef: A, at: Path): DirTree[A] =
     place(at){ (dt: DirTree[A]) => new DirTree(newRef, dt.kids)}

   def delete(at: Path): Option[DirTree[A]] =
     @tailrec
     def loop(remaining: APath[A], result: DirTree[A]): Option[DirTree[A]] =
       remaining match
         case Nil => Some(result)
         case head::tail =>
           val (name, dt) = head
           loop(tail, DirTree(dt.a, dt.kids + (name -> result)))

     this.toClosestAPath(at) match
       case (Nil, Nil) => None
       case (Nil, (name,dt)::apath) => loop(apath,DirTree(dt.a,dt.kids - name))
     // something remains, so we can't insert. We stay where we are
       case other => None
     
end DirTree

