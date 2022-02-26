/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp.fs

import run.cosy.ldp.fs.{Dot, AsInt}

class DotTest extends munit.FunSuite:
   val container = Dot("")
   val acl       = Dot(".acl")
   val card      = Dot("card")
   val cardAcl   = Dot("card.acl")

   test("test remaining") {
     assertEquals(container.remaining(".acl"), Some(List("acl")))
     assertEquals(acl.remaining(".acl"), Some(List()))
     assertEquals(card.remaining("card.acl"), Some(List("acl")))
     assertEquals(card.remaining("card"), Some(List()))
     assertEquals(card.remaining("card.acl"), Some(List("acl")))
     assertEquals(card.remaining("fard.acl"), None)
     assertEquals(card.remaining("card.1.ttl"), Some(List("1", "ttl")))
     assertEquals(cardAcl.remaining("card.acl.1.ttl"), Some(List("1", "ttl")))
   }

   def nameMatch(fn: Dot, name: String, ve: Option[(Int, String)]) =
     name match
        case fn.File(v, ext) => assertEquals(ve, Some(v, ext))
        case x if ve == None => assert(true)
        case err             => assert(false, s"result was $err, but should have been $ve")

   test("pattern match") {
     nameMatch(card, "card.acl", None)
     nameMatch(cardAcl, "card.acl", Some(0, ""))
     nameMatch(card, "card.acl.0.ttl", None)
     nameMatch(cardAcl, "card.acl.0.ttl", Some(0, "ttl"))
     nameMatch(card, "fcard.acl.ttl", None)
     nameMatch(acl, ".acl", Some(0, ""))
   }

   test("verify resource acl") {
     assert(container.hasACR(".acl"))
     assert(container.hasACR(".acl.acl.ttl"))
     assert(acl.hasACR(".acl.acl.ttl"))
     assert(card.hasACR("card.acl.2.ttl"))
   }

   test("pattern match on remaingn") {
     card.remaining("card.0") match
        case Some(List(AsInt(n))) => assert(true)
        case _                    => assert(false, "what happened")
   }
