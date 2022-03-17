package run.cosy.http.auth

import run.cosy.RDF
import run.cosy.RDF.*
import run.cosy.RDF.Prefix.*
import run.cosy.RDF.ops.*
import WacHint.*

class TestWacHint extends munit.FunSuite:
   // examples taken from Auth.md

   def alice(path: String): Rdf#URI = URI("https://alice.name/" + path)
   def bbl: Rdf#URI                 = URI("https://bblfish.net/people/henry/card#me")
   def bblKey: Rdf#URI              = URI("https://bblfish.net/keys/key1#")

   test("empty path construction - no hint") {
     val h1: Option[Path] = Path()
     assert(h1.isDefined)
     assert(h1.get.path.isEmpty)
   }

   test("simple path construction") {
     val auth1 = alice("doc.acl#auth1")
     val h1: Option[Path] = Path(
       NNode(auth1), Rel(wac.agent),
       NNode(bbl), Rev(security.controller),
       NNode(bblKey)
     )
     assert(h1.isDefined)
     assertEquals(h1.get.path.size, 2)
     assertEquals(h1.get.path,
       List(
         (NNode(auth1), Rel(wac.agent), NNode(bbl)),
         (NNode(bbl), Rev(security.controller), NNode(bblKey))
       ))
   }

end TestWacHint
