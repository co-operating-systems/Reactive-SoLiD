/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp.fs

import akka.actor.typed.scaladsl.ActorContext
import akka.http.scaladsl.model.Uri
import run.cosy.ldp.ACLInfo.{ACLActorInfo, MsgACL, NotKnown, OwnACL, ParentAcl}
import run.cosy.ldp.Messages.{ChildTerminated, Cmd}
import run.cosy.ldp.ResourceRegistry
import run.cosy.ldp.fs.APath
import run.cosy.ldp.fs.Attributes.{DirAtt, ManagedResource, SymLink}
import run.cosy.ldp.Messages.{ChildTerminated, Cmd}

/** Enforce a limited actor spawn behavior that can be tested easily for a Container. As a Value
  * Type to avoid having to create an object
  */
class Spawner(val context: ActorContext[BasicContainer.AcceptMsg]) extends AnyVal:

   import org.slf4j.Logger

   def spawn(dir: ActorPath, url: Uri, aclinfo: ACLActorInfo)(
       using reg: ResourceRegistry
   ): Ref =
      import org.slf4j.Logger
      dir match
       case d: DirAtt          => spawnDir(d, aclinfo, url)
       case s: SymLink         => spawnSymLink(s, url)
       case m: ManagedResource => spawnManaged(m, url)

   def spawnDir(dir: DirAtt, defaultAcl: ACLActorInfo, url: Uri)(
       using reg: ResourceRegistry
   ): CRef =
      val dacl: MsgACL =
        defaultAcl match
         case OwnACL => ParentAcl(context.self, false)
         case NotKnown => NotKnown
         case x: ParentAcl  => x
      val name = dir.path.getFileName.toString
      val ref  = context.spawn(BasicContainer(url, dir.path, dacl), name)
      context.watchWith(ref, ChildTerminated(name))
      CRef(dir, ref)

   def spawnSymLink(link: SymLink, url: Uri): RRef =
      val name = link.path.getFileName.toString
      val ref  = context.spawn(Resource(url, link.path, name), name)
      context.watchWith(ref, ChildTerminated(name))
      RRef(link, ref)

   def spawnManaged(link: ManagedResource, url: Uri): SMRef =
      val name = link.path.getFileName.toString
      //there could be other resources than ACResources
      val ref  = context.spawn(ACResource(url, link.path, name), name)
      context.watchWith(ref, ChildTerminated(name))
      SMRef(link, ref)

   def log: Logger = context.log
