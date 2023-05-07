/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp.fs

import run.cosy.ldp.fs.Attributes.ManagedR

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}
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

   /** Builds the Attributes from the information on the files system Fails with the exceptions from
     * Files.readAttributes()
     */
   def forPath(path: Path): Try[APath] =
      import java.nio.file.LinkOption.NOFOLLOW_LINKS
      import java.nio.file.attribute.BasicFileAttributes
      Try {
        val att = Files.readAttributes(path, classOf[BasicFileAttributes], NOFOLLOW_LINKS)
        val linkTo: Option[Path] =
          if att.isSymbolicLink then
             Try(Files.readSymbolicLink(path)).toOption
          else None
        Attributes(path, att, linkTo)
      }

   /** Return the path but only if an actor can be built from it. Todo: The Try may not be worth
     * keeping here, as we don't pass on info if the type was wrong.
     */
   def actorPath(path: Path): Try[ActorPath] =
     forPath(path).collect { case a: ActorPath => a }

   def apply(
       fileName: Path,
       att: BasicFileAttributes,
       linkTo: Option[Path] = None,
       collectedAt: Instant = Instant.now()
   ): APath =
     if att.isDirectory
     then DirAtt(fileName, att, collectedAt)
     else
        linkTo match
         case Some(linkTo) =>
           if linkTo.endsWith(".archive") then
              Archived(fileName, att, collectedAt)
           else if fileName.getFileName.toString == ".acl" then
              // todo: the extensions should be specifiable in a config file
              ManagedR(fileName, linkTo, att, collectedAt)
           else
              SymLink(fileName, linkTo, att, collectedAt)
         case None => OtherAtt(fileName, att, collectedAt)

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

   def createSymLink(dirPath: Path, linkName: String, linkTo: String): Try[SymLink] =
     createLink[SymLink](dirPath, linkName, linkTo)(SymLink.apply)

   def createServerManaged(dirPath: Path, linkName: String, linkTo: String): Try[ManagedR] =
     createLink[ManagedR](dirPath, linkName, linkTo)(ManagedR.apply)

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
   private def createLink[R](dirPath: Path, linkName: String, linkTo: String)(
       app: (Path, Path, BasicFileAttributes, Instant) => R
   ): Try[R] = Try {
     import java.nio.file.Paths
     import java.nio.file.attribute.FileTime
     // todo: verify that LinkTo is valid
     val path            = dirPath.resolve(linkName)
     val linkToPath      = Path.of(linkTo)
     val linkPath: Path  = Files.createSymbolicLink(path, linkToPath)
     val now: Instant    = Instant.now()
     val ftNow: FileTime = FileTime.from(now)
     val att = new BasicFileAttributes:
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

     app(linkPath, linkToPath, att, now)
   }

// todo: may want to remove this later. Just here to help me refactor code
   sealed trait Other           extends Attributes
   sealed trait ManagedResource extends ActorPath

   case class DirAtt private[Attributes] (
       dirPath: Path,
       att: BasicFileAttributes,
       collectedAt: Instant
   ) extends Attributes(att, collectedAt), APath(dirPath), ActorPath

   case class SymLink private[Attributes] (
       symPath: Path,
       to: Path,
       att: BasicFileAttributes,
       collectedAt: Instant
   ) extends Attributes(att, collectedAt), APath(symPath), ActorPath

   /** A Managed Resource such as `.acl`, `card.acl` or `card.meta` can either have a default
     * representation (for acls this is the inclusion of the parent acl) or have a representation
     * saved to disk. This is a Managed Resource, not checked on disk. (todo: we could have a class
     * of MR with an exception as proof that it did not exist!)
     */
   case class DefaultMR private[Attributes] (
       mrpath: Path
   ) extends ManagedResource, APath(mrpath), ActorPath

   /** Container Managed Resource with representation on disk */
   case class ManagedR private[Attributes] (
       mpath: Path,
       to: Path,
       att: BasicFileAttributes,
       collectedAt: Instant
   ) extends ManagedResource, Attributes(att, collectedAt), APath(mpath), ActorPath

   case class OtherAtt private[Attributes] (
       opath: Path,
       att: BasicFileAttributes,
       collectedAt: Instant
   ) extends Other, Attributes(att, collectedAt), APath(opath)

   case class Archived private[Attributes] (
       apath: Path,
       att: BasicFileAttributes,
       collectedAt: Instant
   ) extends Other, Attributes(att, collectedAt), APath(apath)
