/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http.auth

import akka.http.scaladsl.model.Uri
import com.nimbusds.jose.jwk
import com.nimbusds.jose.jwk.JWK
import run.cosy.RDF

class JWKExtractorFn extends munit.FunSuite:
   import RDF.{*, given}
   import RDF.ops.{*, given}
   import RDF.Prefix.security
   import JWKExtractor.{given, *}

   val jwtRsa = ("""{
						|   "kty" : "RSA",
						|   "n"   : "0vx7agoebGcQSuuPiLJXZptN9nndrQmbXEps2aiAFbWhM78LhWx""" +
     "4cbbfAAtVT86zwu1RK7aPFFxuhDR1L6tSoc_BJECPebWKRXjBZCiFV4n3oknjhMs" +
     "tn64tZ_2W-5JsGY4Hc5n9yBXArwl93lqt7_RN5w6Cf0h4QyQ5v-65YGjQR0_FDW2" +
     "QvzqY368QQMicAtaSqzs8KJZgnYb9c7d0zgdAZHzu6qMQvRL5hajrn1n91CbOpbI" +
     "SD08qNLyrdkt-bFTWhAI4vMQFh6WeZu0fM4lFd2NcRwr3XPksINHaQ-G_xBniIqb" +
     "w0Ls1jF44-csFCur-kEgU8awapJzKnqDKgw" + """",
						|   "e"   : "AQAB",
						|   "alg" : "RS256",
						|   "kid" : "2011-04-29"
						| }""").stripMargin

   test("RDF to JWT") {
     import org.w3.banana.*
     import org.w3.banana.syntax.*

     val keyIdStr            = "http://alice.example/key#i"
     val keyId               = URI(keyIdStr)
     val jsLiteral: Rdf#Node = Literal(jwtRsa, rdf.JSON)
     val jwtPg = keyId -- security.publicKeyJwk ->- PointedGraph(jsLiteral, Graph.empty)
     val expectedGraph =
       Graph(
         Triple(keyId, security.publicKeyJwk, Literal(jwtRsa, rdf.JSON))
       )

     assert(jwtPg.graph isIsomorphicWith expectedGraph)

     import JWKExtractor.*

     val kIdInfo: Option[KeyIdInfo] = jwtPg.asKeyIdInfo
     assertEquals(kIdInfo, Some(KeyIdInfo(Uri(keyIdStr), jwk.JWK.parse(jwtRsa))))
   }

   // todo: to go in the other direction, one would actually need to verify the equality of JSON literals,
   // which means taking into account ordering.
