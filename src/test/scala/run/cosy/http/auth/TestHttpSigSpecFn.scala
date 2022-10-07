/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http.auth

import akka.http.scaladsl.model.{
  DateTime,
  HttpMethods,
  HttpRequest,
  HttpResponse,
  MediaRange,
  MediaRanges,
  StatusCodes,
  Uri
}
import akka.http.scaladsl.model.headers.{
  Accept,
  Authorization,
  Date,
  GenericHttpCredentials,
  HttpChallenge,
  Link,
  LinkParam,
  LinkParams,
  LinkValue,
  `WWW-Authenticate`
}
import HttpMethods.*
import akka.http.scaladsl.model.Uri.Host
import run.cosy.http.RDFMediaTypes.*
import run.cosy.http.headers.{Rfc8941, SigInput}
import bobcats.util.StringUtils.*
import run.cosy.akka.http.headers.AkkaMessageSelectors
import run.cosy.http.auth.MessageSignature.*
import run.cosy.http.headers.Rfc8941.*
import run.cosy.http.headers.Rfc8941.SyntaxHelper.*

import scala.language.implicitConversions
import java.time.{Clock, Month, ZoneOffset}
import java.util.Calendar
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import bobcats.SigningHttpMessages.`test-key-rsa-pss`
import bobcats.util.BouncyJavaPEMUtils
import cats.effect.IO
import scodec.bits.ByteVector

/** Tests for the
  * [[https://github.com/solid/authentication-panel/blob/main/proposals/HttpSignature.md HttpSig spec proposal]]
  * This will help make sure the examples are well formed.
  */
class TestHttpSigSpecFn extends munit.FunSuite:
   // we use the same keys as Signing Http Messages
   import bobcats.SigningHttpMessages.{`test-key-rsa-pss`, `test-key-rsa`}
   import run.cosy.http.auth.AkkaHttpMessageSignature.*
   val selectors = new AkkaMessageSelectors(true, Uri.Host("localhost"), 8080)
   import selectors.{given, *}

   val exMediaRanges: Seq[MediaRange] =
     Seq(`application/ld+json`.withQValue(0.9), MediaRanges.`*/*`.withQValue(0.8))

   val resp1Ex = HttpResponse(
     StatusCodes.Unauthorized,
     Seq(
       `WWW-Authenticate`(HttpChallenge("HttpSig", "/comments/", Map())),
       akka.http.scaladsl.model.headers.Host("alice.name"),
       Date(DateTime(2021, 4, 1, 0, 1, 3)),
       Link(LinkValue(Uri("http://www.w3.org/ns/ldp#BasicContainer"), LinkParams.rel("type"))),
       Link(LinkValue(Uri("http://www.w3.org/ns/ldp#Resource"), LinkParams.rel("type"))),
       Link(LinkValue(Uri("/comments/.acl"), LinkParams.rel("acl")))
     )
   )

   val req1Ex = HttpRequest(
     GET,
     Uri("/comments/"),
     Seq(
       Authorization(GenericHttpCredentials(
         "HttpSig",
         Map("proof" -> "sig1", "cred" -> "https://age.com/cred/xyz#")
       )),
       Accept(`text/turtle`.withQValue(1.0), exMediaRanges*)
     )
   )

   given ec: ExecutionContext = scala.concurrent.ExecutionContext.global
   given clock: Clock =
     Clock.fixed(java.time.Instant.ofEpochSecond(16188845000L), java.time.ZoneOffset.UTC)
   import run.cosy.akka.http.headers.{given, *}

   val t  = java.time.LocalDateTime.of(2021, Month.APRIL, 1, 8, 30)
   val t2 = java.time.LocalDateTime.of(2021, Month.APRIL, 1, 23, 45)

   test("intro example") {
     val Some(si) = SigInput(IList(`@request-target`.sf, authorization.sf)(
       Token("keyid")   -> sf"/keys/alice#",
       Token("created") -> SfInt(t.toInstant(ZoneOffset.UTC).toEpochMilli / 1000),
       Token("expires") -> SfInt(t2.toInstant(ZoneOffset.UTC).toEpochMilli / 1000)
     ))
     val Success(req1signingStr) = req1Ex.signingStr(si)
     val signerF: IO[ByteVector => IO[ByteVector]] =
       for
          spec <- IO.fromTry(BouncyJavaPEMUtils.getPrivateKeySpec(
            `test-key-rsa-pss`.privatePk8Key,
            `test-key-rsa-pss`.keyAlg
          ))
          f <- bobcats.Signer[IO].build(spec, bobcats.AsymmetricKeyAlg.`rsa-pss-sha512`)
       yield f

     val respio =
       for f <- signerF
       yield req1Ex.withSigInput(Rfc8941.Token("sig1"), si, f)

//		println("----401 response----")
//		println(resp1Ex.documented.toRfc8792single())
//		println("----partial request to be signed---")
//		println(req1Ex.documented.toRfc8792single())
//		println("---signing string----")
//		println(req1signingStr.toRfc8792single())
//		println("----signed request----")
//		println(req1signed.documented.toRfc8792single())
//		import com.nimbusds.jose.jwk.JWK
//		val jwk = JWK.parseFromPEMEncodedObjects(pubkeyPEM)
//		println("----json public key---")
//		println(jwk.toJSONString)
//		//println(sigReq.get.documented)
   }
