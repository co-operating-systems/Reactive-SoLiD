/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http

import _root_.akka.http.scaladsl.model.{HttpRequest, Uri}
import _root_.akka.http.scaladsl.model.headers.{Link, LinkParams, LinkValue, RawHeader}
import munit.*
import munit.Location.*

import scala.util.Success

/** for more possible test cases and examples see
  * [[https://github.com/akka/akka-http/blob/v10.2.4/akka-http-tests/src/test/scala/akka/http/scaladsl/server/ModeledCustomHeaderSpec.scala ModeledCustomHEaderSpec.scala]]
  */
class TestCustomHeaders extends munit.FunSuite:
   import _root_.run.cosy.http.headers.*
   import _root_.run.cosy.akka.http.headers.Encoding.*
   test("Slug") {
     val blg = new Slug("blog".asClean)
     assertEquals(blg.value, "blog", blg)

     val RawHeader(k2, v2) = new Slug("blog 2".asClean)
     assertEquals(k2, "Slug")
     assertEquals(v2, "blog+2")

     val sv: Slug = new Slug("slug:value".asClean)
     assertEquals(sv.value, "slug%3Avalue")
     RawHeader("Slug", "slug%3Avalue") match
     case Slug(x) => assertEquals(x.toString, "slug:value")
     case _       => fail

     // will match, header keys are case insensitive
     val atomEg   = Slug("The Beach at Sète".asClean)
     val Slug(v5) = atomEg
     assertEquals(atomEg.value, "The+Beach+at+S%C3%A8te", atomEg)
     assertEquals(atomEg.lowercaseName, "slug")
     assertEquals(v5.toString, "The Beach at Sète", atomEg)

     val rawAtom = RawHeader("Slug", "The Beach at S%C3%A8te")
     rawAtom match
     case Slug(x) => assertEquals(x.toString, "The Beach at Sète")
     case _       => fail
     assertEquals(rawAtom.name, "Slug")
     assertEquals(rawAtom.value, "The Beach at S%C3%A8te")

     intercept[MatchError] {
       // won't match, different header name
       val Slug(_) = Link(Uri("page2"), LinkParams.next)
     }
   }

   test("Slug using response.header[...] syntax") {
     val slug    = Slug("blog 4".asClean)
     val request = HttpRequest().addHeader(slug)
     assertEquals(request.header[Slug], Option(slug))
     request.header[Slug] match
     case Some(RawHeader(key, value)) => assertEquals(value, "blog+4")
     case _                           => fail
   }
