/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp.fs

import akka.actor.typed.scaladsl.ActorContext
import akka.http.scaladsl.model.Uri
import run.cosy.ldp.{ACInfo, ResourceRegistry, SolidPostOffice}
import run.cosy.ldp.Messages.{ChildTerminated, Cmd}
import run.cosy.ldp.fs.APath
import run.cosy.ldp.fs.Attributes.{DirAtt, ManagedR, ManagedResource, SymLink}
import run.cosy.ldp.Messages.{ChildTerminated, Cmd}

/** Enforce a limited actor spawn behavior that can be tested easily for a Container. As a Value
  * Type to avoid having to create an object
  */
class Spawner(val context: ActorContext[BasicContainer.AcceptMsg]) extends AnyVal:

   import org.slf4j.Logger

   // todo: what happens when actor is spawned? That should also pass the aclinfo...
   // todo: we return an Option because we only seem to need three types. Perhaps we can find a
   //   way to ignore the others?
   def spawn(dir: APath, url: Uri, aclinfo: ACInfo)(
       using SolidPostOffice
   ): Option[Ref] =
      import org.slf4j.Logger
      dir match
       case d: DirAtt   => Some(spawnDir(d, url, aclinfo))
       case s: SymLink  => Some(spawnSymLink(s, url, aclinfo))
       case m: ManagedR => Some(spawnManaged(m, url))
       case _           => None

   // todo: document why in container we need to pass ACInfo along and not for resource
   def spawnDir(dir: DirAtt, url: Uri, defaultAcl: ACInfo)(
       using SolidPostOffice
   ): CRef =
      val name = dir.path.getFileName.toString
      val ref  = context.spawn(BasicContainer(url, dir.path, defaultAcl), name)
      context.watchWith(ref, ChildTerminated(name))
      CRef(dir, ref)

   def spawnSymLink(link: SymLink, url: Uri, aci: ACInfo): RRef =
      val name = link.path.getFileName.toString
      val ref  = context.spawn(Resource(url, link.path, name, aci), name)
      context.watchWith(ref, ChildTerminated(name))
      RRef(link, ref)

   def spawnManaged(link: ManagedR, url: Uri): SMRef =
      val name = link.path.getFileName.toString
      // there could be other resources than ACResources
      val ref = context.spawn(ACResource(url, link.path, name), name)
      context.watchWith(ref, ChildTerminated(name))
      SMRef(link, ref)

   def log: Logger = context.log
