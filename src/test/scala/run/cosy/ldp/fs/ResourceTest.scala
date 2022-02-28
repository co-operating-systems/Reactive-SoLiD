/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp.fs

import akka.http.scaladsl.model.Uri
import java.io.File
import run.cosy.http.util.UriX.*

class ResourceTest extends munit.FunSuite:
   val bbl: Uri = Uri("https://bblfish.net")

   test("test basic ACResource functions") {
     val ac1 = new ACResource(bbl / ".acl", new File("/tmp/.acl").toPath, null)
     assertEquals(ac1.aclName, ".acl")
     assertEquals(ac1.dotLinkName.isACR, true)
     assertEquals(ac1.dotLinkName.parts, List("", "acl"))
     assertEquals(ac1.dotLinkName.isContainerAcl, true)
   }
