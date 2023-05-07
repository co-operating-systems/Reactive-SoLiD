/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http.util

import run.cosy.http.util.UriX.*
import akka.http.scaladsl.model.Uri

class UriUtilTest extends munit.FunSuite:

   val card       = Uri("https://bblfish.net/people/henry/card.ttl#me")
   val ppl        = Uri("https://bblfish.net/people")
   val pplDir     = Uri("https://bblfish.net/people/")
   val bbl        = Uri("https://bblfish.net")
   val bblRt      = Uri("https://bblfish.net/")
   val bblDblSlsh = Uri("https://bblfish.net//")
   val bblIndx    = Uri("https://bblfish.net/index")

   test("uri.withSlash") {
     assertEquals(bbl.withSlash, bblRt)
     assertEquals(ppl.withSlash, pplDir)
   }

   test("test path diff") {
     assertEquals(bbl.path.diff(bblRt.path), Option(Uri.Path./))
     assertEquals(bbl.path.diff(bbl.path), Option(Uri.Path.Empty))
     assertEquals(bblRt.path.diff(bbl.path), None)
     assertEquals(bbl.path.diff(bblDblSlsh.path),
       Option(Uri.Path.Slash(Uri.Path.Slash(Uri.Path.Empty))))
     assertEquals(bblRt.path.diff(bblDblSlsh.path), Option(Uri.Path./))
     assertEquals(bbl.path.diff(bblIndx.path), Option(Uri.Path./("index")))
     assertEquals(bbl.path.diff(card.path), Option(card.path))
     assertEquals(bblRt.path.diff(card.path), Option(card.path.tail))
     assertEquals(ppl.path.diff(bblIndx.path), None)
     assertEquals(ppl.path.diff(card.path), Some(card.path.tail.tail))
     assertEquals(pplDir.path.diff(card.path), Some(card.path.tail.tail.tail))
   }

   test("uri.filename") {
     assertEquals(card.fileName, Some("card.ttl"))
     assertEquals(bbl.fileName, None)
     assertEquals(bblRt.fileName, None)
   }

   test("uri.toPath") {
     assertEquals(bbl.path.components, (Nil, None))
     assertEquals(bblRt.path.components, (Nil, None))
     assertEquals(bblIndx.path.components, (Nil, Some("index")))
     assertEquals(ppl.path.components, (Nil, Some("people")))
     assertEquals(pplDir.path.components, (List("people"), None))
     assertEquals(card.path.components, (List("people", "henry"), Some("card.ttl")))
   }

   test("uri.withoutSlash") {
     assertEquals(card.withoutSlash, card)
     assertEquals(bbl.withoutSlash, bbl)
     assertEquals(bblRt.withoutSlash, bbl)
   }

   test("uri.sibling(bro)") {
     assertEquals(card.sibling("card"), Uri("https://bblfish.net/people/henry/card#me"))
     assertEquals(bbl.sibling("card"), Uri("https://bblfish.net/card"))
     assertEquals(bblRt.sibling("card"), Uri("https://bblfish.net/card"))
   }

   test("uri.ancestorOf(...)") {
     assert(bbl.ancestorOf(bbl))
     assert(bbl.ancestorOf(card))
     assert(bblRt.ancestorOf(card))
     assert(bblRt.ancestorOf(bblRt))
     assert(bblRt.ancestorOf(bblIndx))
   }

   test("path.container") {
     assertEquals(card.path.container, Uri.Path("/people/henry/"))
     assertEquals(Uri.Path./("").container, Uri.Path./)
   }
