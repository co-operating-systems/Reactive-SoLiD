/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp.fs

class TestAttributesFn extends munit.FunSuite:
   import java.nio.file.{Files, Path}
   import run.cosy.ldp.testUtils.TmpDir

   val tmpDir = FunFixture[Path](
     setup = test =>
       TmpDir.createDir("cosyTest"),
     teardown = dir => TmpDir.deleteDir(dir)
   )

   tmpDir.test("create SymLink") { (dirPath: Path) =>
      println(s"path=$dirPath")
      import java.nio.file.Paths
      import scala.util.Try
      // create symlink
      val att: Try[Attributes.SymLink] = Attributes.createLink(dirPath, "hello", "hello.txt")
      assert(att.isSuccess)
      val sl: Attributes.SymLink = att.get
      assertEquals(sl.path, dirPath.resolve("hello"), att)
      assertEquals(sl.att.isSymbolicLink, true, att)
      assertEquals(sl.to, Paths.get("hello.txt"), att)

      // teest the created symlink directly
      val slatTry: Try[APath] = Attributes.forPath(sl.path)
      assert(slatTry.isSuccess)
      val slatt: APath = slatTry.get
      slatt match
       case symLink: Attributes.SymLink =>
         import java.nio.file.LinkOption
         assertEquals(symLink.path, dirPath.resolve("hello"))
         assertEquals(symLink.to.toString, "hello.txt", slatt)
         assertEquals(sl.to, symLink.to, slatt)
         assert(Files.exists(symLink.path, LinkOption.NOFOLLOW_LINKS))
         assert(!Files.exists(symLink.path))
         assert(!Files.exists(dirPath.resolve("hello.txt")))
       case _ => assert(false, slatt)

   }
