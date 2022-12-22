/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http.auth
import akka.http.scaladsl.model.Uri
import com.nimbusds.jose.Algorithm
import com.nimbusds.jose.jwk
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jwt.{JWTParser, PlainJWT}
import org.w3.banana.binder.PGBinder
import run.cosy.http.auth.SecurityPrefix
import run.cosy.RDF
import org.w3.banana.{FOAFPrefix, PointedGraph, PointedGraphs}

import java.security.interfaces.RSAKey
import scala.util.{Failure, Success, Try}
import scala.util.control.NoStackTrace
import RDF.{given, *}, ops.given

object JWKExtractor:

   import jwk.{JWK, JWKMatcher, KeyUse, OctetSequenceKey}
   import recordBinder.*

   import org.w3.banana.diesel.PointedGraphW
   import org.w3.banana.PointedGraphs

   val security = SecurityPrefix[Rdf]
   val pubKey   = property[String](security.publicKeyJwk)

   // todo: later we may want to use matchers to make sure we can use the key as intended
   val jwkMatcher = new JWKMatcher.Builder().keyUse(KeyUse.SIGNATURE).build()

   extension (pg: PointedGraph[Rdf])
     def asKeyIdInfo: Option[KeyIdInfo] =
       if pg.pointer.isURI then
          (pg / security.publicKeyJwk).nodes.collectFirst {
            case JWkey(jwk) => jwk
          }.map { jk => KeyIdInfo(Uri(pg.pointer.asInstanceOf[Rdf#URI].getString), jk) }
       else None

   def toJavaCrypt(jwk: JWK) =
     jwk.getAlgorithm

end JWKExtractor

// the binder system does not work here as it does not allow one to set the location of the id field
//	given PGBinder[Rdf, KeyIdInfo] =
//		pgbWithId[KeyIdInfo]((kid: KeyIdInfo) => kid.id.toRdf)
//			.apply(pubKey)(
//				fromJwK andThen(_.get), //note it looks like Option should be on the other side!
//				(kid: KeyIdInfo) => Some(kid.pka.toJSONString)) // withClasses classUris

case class KeyIdInfo(id: Uri, pka: JWK)

object JWkey:
   def unapply(jwkStr: String): Option[JWK] = fromJwK(jwkStr).toOption
   def unapply(node: Rdf#Node): Option[JWK] =
     node.fold(
       uri => None,
       bnode => None,
       literal => unapply(literal.lexicalForm) // todo: verify datatype
     )

   def fromJwK(jwkStr: String): Try[JWK] =
     scala.util.Try(jwk.JWK.parse(jwkStr))
