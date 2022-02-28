/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp.fs

import java.nio.file.{Files, Path}
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import scala.util.Try

/** @param path
  *   path of resource in the directory (is not proof that it exists) which may be associated with
  *   an actor.
  */
sealed trait APath(val path: Path)

/** Paths that have potentially associated actors */
sealed trait ActorPath extends APath

/** @param att
  *   proof of existence, in the form of file attributes
  * @param collectedAt
  *   instant in time the file attributes were collected at - important to keep freshness info
  */
sealed trait Attributes(att: BasicFileAttributes, collectedAt: Instant)

object Attributes:
   // todo: currently we use attributes to generate the type, but arguably, when we create an object, we don't
   //     need attributes, or even verifying where the link points to since we just created it.
   //     So we could add methods to create a link, and fill in the attributes and linkedTo object

   // todo: I don't get the feeling that Java Paths are efficiently encoded. It looks like there is room for
   //   optimization. Perhaps using Akka paths would be better.

   import java.nio.file.Paths
   import java.nio.file.attribute.FileTime

   /** Fails with the exceptions from Files.readAttributes() */
   def forPath(path: Path): Try[APath] =
      import java.nio.file.LinkOption.NOFOLLOW_LINKS
      import java.nio.file.attribute.BasicFileAttributes
      Try {
        if path.getFileName.toString.contains('.') then
           DefaultMR(path)
        else
           val att = Files.readAttributes(path, classOf[BasicFileAttributes], NOFOLLOW_LINKS)
           Attributes(path, att)
      }

   /** Return the path but only if an actor can be built from it. Todo: The Try may not be woth
     * keeping here, as we don't pass on info if the type was wrong.
     */
   def actorPath(path: Path): Try[ActorPath] = forPath(path).collect {
     case a: ActorPath => a
   }

   def apply(
       fileName: Path,
       att: BasicFileAttributes,
       collectedAt: Instant = Instant.now()
   ): APath =
     if att.isDirectory then DirAtt(fileName, att, collectedAt)
     else if att.isSymbolicLink then
        if fileName.getFileName.toString.contains('.') then
           // todo: we want a lot more checks here - there should only be a limited list of such resources
           ManagedR(fileName, att, collectedAt)
        else
           val linkTo = Files.readSymbolicLink(fileName)
           if linkTo.endsWith(".archive") then
              Archived(fileName, att, collectedAt)
           else SymLink(fileName, linkTo, att, collectedAt)
     else OtherAtt(fileName, att, collectedAt)

   def createDir(inDir: Path, dirName: String): Try[DirAtt] = Try {
     val path       = inDir.resolve(dirName)
     val newDirPath = Files.createDirectory(path)
     val now        = Instant.now()
     val ftNow      = FileTime.from(now)
     DirAtt(
       newDirPath,
       new BasicFileAttributes:
          override def lastModifiedTime(): FileTime = ftNow
          override def lastAccessTime(): FileTime   = ftNow
          override def creationTime(): FileTime     = ftNow
          override def isRegularFile: Boolean       = false
          override def isDirectory: Boolean         = true
          override def isSymbolicLink: Boolean      = false
          override def isOther: Boolean             = false
          override def size(): Long                 = 0
          override def fileKey(): AnyRef            = null
       ,
       now
     )
   }

   /** Create a Symbolic Link
     *
     * @param dirPath
     *   the path of the directory in which the link will be placed
     * @param linkName
     *   the name of the symbolic link
     * @param linkTo
     *   the relative name of the file linked to
     * @return
     *   The full path to the symbolic link relative to the base where the JVM is running
     */
   def createLink(dirPath: Path, linkName: String, linkTo: String): Try[SymLink] = Try {
     import java.nio.file.Paths
     import java.nio.file.attribute.FileTime
     val path     = dirPath.resolve(linkName)
     val linkPath = Files.createSymbolicLink(path, Paths.get(linkTo))
     val now      = Instant.now()
     val ftNow    = FileTime.from(now)
     // avoid checking the file system again, create the obvious answer
     SymLink(
       linkPath,
       Paths.get(linkTo),
       new BasicFileAttributes:
          import java.nio.file.attribute.FileTime
          override def lastModifiedTime(): FileTime = ftNow
          override def lastAccessTime(): FileTime   = ftNow
          override def creationTime(): FileTime     = ftNow
          override def isRegularFile: Boolean       = false
          override def isDirectory: Boolean         = false
          override def isSymbolicLink: Boolean      = true
          override def isOther: Boolean             = false
          override def size(): Long                 = 0
          override def fileKey(): AnyRef            = null
       ,
       now
     )
   }

   // todo: may want to remove this later. Just here to help me refactor code
   sealed trait Other           extends Attributes
   sealed trait ManagedResource extends ActorPath

   case class DirAtt private[Attributes] (
       override val path: Path,
       att: BasicFileAttributes,
       collectedAt: Instant
   ) extends Attributes(att, collectedAt), APath(path), ActorPath

   case class SymLink private[Attributes] (
       override val path: Path,
       to: Path,
       att: BasicFileAttributes,
       collectedAt: Instant
   ) extends Attributes(att, collectedAt), APath(path), ActorPath

   /** A Managed Resource such as `.acl`, `card.acl` or `card.meta` can either have a default
     * representation (for acls this is the inclusion of the parent acl) or have a representation
     * saved to disk. This is a Managed Resource, not checked on disk. (todo: we could have a class
     * of MR with an exception as proof that it did not exist!)
     */
   case class DefaultMR private[Attributes] (
       override val path: Path
   ) extends ManagedResource, APath(path), ActorPath

   /** Managed Resource with representation on disk */
   case class ManagedR private[Attributes] (
       override val path: Path,
       att: BasicFileAttributes,
       collectedAt: Instant
   ) extends ManagedResource, Attributes(att, collectedAt), APath(path), ActorPath

   case class OtherAtt private[Attributes] (
       override val path: Path,
       att: BasicFileAttributes,
       collectedAt: Instant
   ) extends Other, Attributes(att, collectedAt), APath(path)

   case class Archived private[Attributes] (
       override val path: Path,
       att: BasicFileAttributes,
       collectedAt: Instant
   ) extends Other, Attributes(att, collectedAt), APath(path)
