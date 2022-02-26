/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp.fs

import akka.stream.{Attributes, Outlet, SourceShape}
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler, StageLogging}

import java.io.IOException
import java.nio.file.{Files, FileVisitOption, Path}
import java.nio.file.attribute.BasicFileAttributes
import java.util.function.BiPredicate
import java.util.stream
import javax.naming.directory.BasicAttribute

object DirectoryList:

   import java.nio.file.{Path, SimpleFileVisitor, Files}

   def apply(
       dir: Path,
       depth: Int = 1,
       options: FileVisitOption*
   )(
       matcher: (Path, BasicFileAttributes) => Boolean = (p, a) => true
   ): GraphStage[SourceShape[(Path, BasicFileAttributes)]] =
     new DirectoryList(dir, depth, matcher, options*)

/** For explanation see Henry Story's answer to Stack Overflow question
  * [[https://stackoverflow.com/a/66713743/396079 How to get Streams of File Attributes from the FileSystem?]]
  * @param dir
  *   the dir to look at
  * @param maxDepth
  *   the depth of directories to go into
  * @param matcher
  *   a filter to select subsets of the files
  */
class DirectoryList(
    dir: Path,
    maxDepth: Int = 1,
    matcher: (Path, BasicFileAttributes) => Boolean = (p, a) => true,
    options: FileVisitOption*
) extends GraphStage[SourceShape[(Path, BasicFileAttributes)]]:
   import scala.jdk.FunctionConverters.*
   import scala.jdk.OptionConverters.*

   val out: Outlet[(Path, BasicFileAttributes)] = Outlet("PathAttributeSource")
   override val shape                           = SourceShape(out)

   override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
     new GraphStageLogic(shape):
        private var next: (Path, BasicFileAttributes) = _

        def append(path: Path, att: BasicFileAttributes): Boolean =
           val matched = matcher(path, att)
           if matched then next = (path, att)
           matched

        private val pathStream = Files.find(dir, maxDepth, append.asJava, options*)
        private val sit        = pathStream.iterator()

        setHandler(
          out,
          new OutHandler:
             override def onPull(): Unit =
               if sit.hasNext then
                  sit.next()
                  push(out, next)
               else
                  pathStream.close()
                  complete(out)

             override def onDownstreamFinish(cause: Throwable): Unit =
                pathStream.close()
                super.onDownstreamFinish(cause)
        )
end DirectoryList
