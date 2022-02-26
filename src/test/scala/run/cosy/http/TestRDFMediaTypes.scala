/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http

import run.cosy.http.RDFMediaTypes.{`application/ld+json`}

class TestRDFMediaTypes extends munit.FunSuite:

   test("rdf types are all there") {
     assertEquals(RDFMediaTypes.all.toSet.size, 11)
   }

   test("extension are set") {
     assertEquals(`application/ld+json`.fileExtensions, List("jsonld"))
     assertEquals(`application/ld+json`.mainType, "application")
     assertEquals(`application/ld+json`.subType, "ld+json")
   }
