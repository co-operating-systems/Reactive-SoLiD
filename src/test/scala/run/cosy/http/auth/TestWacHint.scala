/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http.auth

import run.cosy.RDF
import run.cosy.RDF.*
import run.cosy.RDF.Prefix.*
import run.cosy.RDF.ops.*
import WacHint.{BNode as whBNode, *}
import cats.data.NonEmptyList

class TestWacHint extends munit.FunSuite:
   // examples taken from Auth.md

   def alice(path: String): Rdf#URI = URI("https://alice.name/" + path)
   def bbl: Rdf#URI                 = URI("https://bblfish.net/people/henry/card#me")
   def bblKey: Rdf#URI              = URI("https://bblfish.net/keys/key1#")

   test("empty path construction - no hint") {
     val Some(h1) = Path()
     assert(h1.path.isEmpty)
     assert(h1.groupByResource.isEmpty)
   }

   val auth1 = alice("doc.acl#auth1")

   test("simple path construction with intermediate blank node") {
     val Some(p1) = Path(
       NNode(auth1), Rel(wac.agent),
       whBNode(), Rev(security.controller),
       NNode(bblKey)
     )
     val expectedPathLst = List[Triple](
       (NNode(auth1), Rel(wac.agent), whBNode()),
       (whBNode(), Rev(security.controller), NNode(bblKey))
     )
     assertEquals(p1.path, expectedPathLst)
     assertEquals(p1.groupByResource,
       List(NonEmptyList.fromListUnsafe(expectedPathLst)))
   }

   test("simple path construction with 2 jumps") {

     val Some(p1) = Path(
       NNode(auth1), Rel(wac.agent),
       NNode(bbl), Rev(security.controller),
       NNode(bblKey)
     )
     assertEquals(p1.path.size, 2)
     val expectedPathLst = List[Triple](
       (NNode(auth1), Rel(wac.agent), NNode(bbl)),
       (NNode(bbl), Rev(security.controller), NNode(bblKey))
     )
     assertEquals(p1.path, expectedPathLst)
     assertEquals(p1.groupByResource,
       List(NonEmptyList.one(expectedPathLst.head), NonEmptyList.one(expectedPathLst.tail.head)))
   }

   test("more complex graph construction") {
     val Some(p1) = Path(
       NNode(auth1), Rel(wac.agentGroup),
       whBNode(), Rel(foaf.member),
       NNode(bbl), Rev(security.controller),
       NNode(bblKey)
     )
     val expectedPathLst = List[Triple](
       (NNode(auth1), Rel(wac.agentGroup), whBNode()),
       (whBNode(), Rel(foaf.member), NNode(bbl)),
       (NNode(bbl), Rev(security.controller), NNode(bblKey))
     )
     assertEquals(p1.path, expectedPathLst)
     assertEquals(p1.groupByResource,
       List(NonEmptyList.fromListUnsafe(expectedPathLst.take(2)),
         NonEmptyList.one(expectedPathLst.last)))

   }

end TestWacHint
