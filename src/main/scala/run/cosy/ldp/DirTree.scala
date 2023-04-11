package run.cosy.ldp

import scala.annotation.tailrec
import scala.collection.immutable.HashMap

/** Immutable Rose tree structure to keep path to references could perhaps use
  * {{{
  * type DirTree[R,A] = cats.free.Cofree[HashMap[String,_],A]
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

   /** A Path of DirTree[R,A]s is used to take apart a DirTree[R,A] structure, in the reverse
     * direction. Note the idea is that each link (name, dt) points from dt via name in the hashMap
     * to the next deeper node. The APath is in reverse direction so we have List( "newName" <- dir2
     * , "dir2in1" <- dir1 , "dir1" <- rootDir ) An empty List would just refer to the root
     * DirTree[R,A]
     */
   type ALink[R, A] = (String, DirTree[R, A])
   type APath[R, A] = List[ALink[R, A]]
   // the first projection is either
   //   -Right: the object at the end of the path
   //   -Left: the remaining path. If it is Nil then the path is pointing into a position to which one can add
   // the second is the A Path, from the object to the root so that the object can be reconstituted
   type SearchAPath[R, A] = (Either[Path, DirTree[R, A]], APath[R, A])

   extension [R, A](thizDT: DirTree[R, A])
      /** @param at
        *   path to resource A
        * @return
        *   a pair of the remaining path and A
        */
      @tailrec
      def findClosest(at: Path): (Path, R, A) =
        at match
         case Nil => (at, thizDT.ref, thizDT.attr)
         case name :: tail =>
           thizDT.kids.get(name) match
            case None => (at, thizDT.ref, thizDT.attr)
            case Some(tree) => tree.findClosest(tail)
      end findClosest

      /**
        * This function is very specific to our needs. It is easier to add it to this lib for
        * testing, but it should perhaps be in the ResourceRegistry class.
        * The first R is the ActorRef we are looking for, the second R is the Actor that has an acl.
        * It should always exist.
        *
        * Find the first known R following path, and from there follow up the tree to find the next
        * closest R that satisifies prop.
        * @return a triple of remaining path, the closest R on path, and from there back up the
        *         closest R that satisfies prop
        */
      def findClosestRs(path: Path)(prop: A => Boolean): (Path, R, Option[R]) =
        val res: SearchAPath[R, A] = thizDT.toClosestAPath(path)
        def find(p: APath[R,A]): Option[R] = p.collectFirst{
          case (_, dt) if prop(dt.attr) => dt.ref
        }
        res match
          case (Right(o), pathToRoot) => (List(), o.ref, find(("",o):: pathToRoot))
          case (Left(remainingPath), Nil) =>
            (remainingPath, thizDT.ref, find(List(""->thizDT)))
          case (Left(remainingPath), head::tail) =>
            (head._1::remainingPath, head._2.ref, find(head::tail))
      end findClosestRs
      
      /** note we can only find the closest path to something if the path is not empty */
      def toClosestAPath(path: Path): SearchAPath[R, A] =
         @tailrec
         def loop(dt: DirTree[R, A], path: Path, result: APath[R, A]): SearchAPath[R, A] =
           path match
            case Nil => (Right(dt), result)
            case name :: rest =>
              if dt.kids.isEmpty then (Left(rest), (name, dt) :: result)
              else
                 dt.kids.get(name) match
                  case None => (Left(rest), (name, dt) :: result)
                  case Some(dtchild) =>
                    loop(dtchild, rest, (name, dt) :: result)
         end loop
         loop(thizDT, path, Nil)
      end toClosestAPath

end DirTree

/** Another option could have been where X could be a pair (R,A)
  * {{{
  * import cats.free.Cofree
  * type HMap[X] = HashMap[String,X]
  * type DirTree[R,A] = Cofree[HMap[_],A]
  * }}}
  *
  * @tparam R
  *   Actor Reference
  * @tparam A
  *   Attributes for the Container. Optionality has to be provided in A, providing for greater
  *   implementation flexibility. To illustrate: we could have chosen to use an Option[A] type, but
  *   that would make having the DefaultAcl type NotKnown redundant, or having a potentially empty
  *   List or Set of Attributes
  */
case class DirTree[R, A](
    ref: R,
    attr: A,
    kids: HashMap[String, DirTree[R, A]] = HashMap()
):
   import DirTree.*

   /** Insert a new A at given path, and ignore any existing subtrees at that position. This makes
     * sense for our main use case of ActorRef, since changing an actorRef would change all the
     * subtree
     */
   final def insert(newRef: R, at: Path, withAtrs: A): DirTree[R, A] =
     place(at) {
       case (Left(Nil), (name, dt) :: apath) =>
         (apath, dt.copy(kids = dt.kids + (name -> DirTree(newRef, withAtrs))))
       case (Right(_), apath) => (apath, DirTree(newRef, withAtrs))
       // something remains, so we can't insert. We stay where we are
       case _ => (Nil, this)
     }

   final def place(at: Path)(
       buildRootDT: SearchAPath[R, A] => (APath[R, A], DirTree[R, A])
   ): DirTree[R, A] =
      @tailrec
      def loop(path: APath[R, A], result: DirTree[R, A]): DirTree[R, A] =
        path match
         case Nil                 => result
         case (name, obj) :: tail => loop(tail, obj.copy(kids = obj.kids + (name -> result)))

      // note here we loose any subtrees below the insertion.
      val cpath: SearchAPath[R, A] = this.toClosestAPath(at)
      val (p, dt)                  = buildRootDT(cpath)
      loop(p, dt)
   end place

   /** we need to replace the attribute A at at path, but keep everything else the same. This
     * happens when a property of the ref changes, but not the tree structure, eg. if we keep the
     * same ActorRef but change the info about acls
     */
   final def replace(newAttr: A, at: Path): DirTree[R, A] =
     place(at) {
       case (Right(o), (name, dt) :: apath) =>
         (apath, dt.copy(kids = dt.kids + (name -> o.copy(attr = newAttr))))
       case (Right(o), Nil) => (Nil, o.copy(attr = newAttr))
       // something remains, so we can't insert. We stay where we are
       case _ => (Nil, this)
     }

   def delete(at: Path): Option[DirTree[R, A]] =
     if at.isEmpty then None
     else
        Some(place(at) {
          case (Left(Nil), (name, dt) :: apath) => (apath, dt.copy(kids = dt.kids - name))
          case (Right(_), (name, dt) :: apath)  => (apath, dt.copy(kids = dt.kids - name))
          // something remains, so we can't insert. We stay where we are
          case _ => (Nil, this)
        })

end DirTree
