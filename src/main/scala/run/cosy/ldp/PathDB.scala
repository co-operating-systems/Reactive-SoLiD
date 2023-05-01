/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp

import akka.http.scaladsl.model.Uri

import java.util.concurrent.atomic.AtomicReference

/** A Path to T Database that can be access and altered by multiple threads
  *
  * todo: (see below) could one pass Root DirTree to constructor? That would require the actorRef
  * for the root to be known in advance. That means this would have to be created by the
  *
  * @tparam R
  *   the type of the Reference
  * @tparam A
  *   the type of the attributes
  */
trait PathDB[R, A]:

   import DirTree.Path
   import run.cosy.Solid.pathToList

   def hasAcl(a: A): Boolean

   /** todo: remove Option? That would require root DirTree to be passed in constructor. it would
     * avoid having to map over the option all the time.
     */
   val pathMap: AtomicReference[Option[DirTree[R, A]]] = new AtomicReference(None)

   /** add an actor Ref to the local DB */
   def addActorRef(path: Uri.Path, actorRef: R, optAttr: A): Unit =
     pathMap.updateAndGet { tree =>
        val pathLst = pathToList(path)
        tree match
         case None      => if (pathLst.isEmpty) then Some(DirTree(actorRef, optAttr)) else None
         case Some(ref) => Some(ref.insert(actorRef, pathLst, optAttr))
     }

   def setAttributeAt(path: Uri.Path, attr: A): Unit =
     pathMap.updateAndGet(_.map(_.replace(attr, pathToList(path))))

   /** remove an actor Ref from the local DB */
   def removePath(path: Uri.Path): Unit =
     pathMap.updateAndGet(_.flatMap(_.delete(pathToList(path))))

   /** get actorRef for a path todo: remove the Option(...) because we only have None when the root
     * container which will be before
     *
     * @return
     *   Some(path, ref, aclRefOpt) where * path is the remaining path to the actor ref, * and
     *   aclRefOpt is the latest place a ac was found or None if there is no root container
     *   registered... (should never happen hence todo)
     */
   def getActorRef(path: Path): Option[(Path, R, Option[R])] =
     pathMap.getPlain.map(_.findClosestRs(path)(hasAcl))

end PathDB
