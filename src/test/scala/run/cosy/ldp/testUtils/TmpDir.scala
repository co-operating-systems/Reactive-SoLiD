/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp.testUtils

import java.nio.file.{Files, Path}

object TmpDir:

   def createDir(prefix: String): Path = Files.createTempDirectory(prefix)

   def deleteDir(dirPath: Path): Unit =
      import java.nio.file.{Path, FileVisitOption, FileVisitor}
      import java.util.Comparator
      import scala.jdk.StreamConverters.{given, *}
      val files: LazyList[Path] =
        Files.walk(dirPath).sorted(Comparator.reverseOrder()).toScala(LazyList)
      files.map(_.toFile).foreach(_.delete)
   end deleteDir
