/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http.auth

import akka.http.scaladsl.model
import akka.http.scaladsl.model.{HttpEntity, HttpHeader, HttpMessage, HttpRequest, Uri}
import akka.http.scaladsl.model.headers.{
  Authorization,
  GenericHttpCredentials,
  HttpChallenge,
  HttpCredentials,
  OAuth2BearerToken,
  `Content-Type`
}
import akka.http.scaladsl.server.AuthenticationFailedRejection.{
  CredentialsMissing,
  CredentialsRejected
}
import akka.http.scaladsl.server.{AuthenticationFailedRejection, Directive1, RequestContext}
import akka.http.scaladsl.server.Directives.{
  AsyncAuthenticator,
  AuthenticationResult,
  authenticateOrRejectWithChallenge,
  extract,
  extractCredentials,
  extractRequestContext,
  provide
}
import akka.http.scaladsl.server.directives.{
  AuthenticationDirective,
  AuthenticationResult,
  Credentials
}
import akka.http.scaladsl.server.directives.BasicDirectives.extractExecutionContext
import akka.http.scaladsl.server.directives.RouteDirectives.reject
import akka.http.scaladsl.util.FastFuture
import cats.MonadError
import cats.effect.{IO, kernel}
import cats.effect.unsafe.IORuntime
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.util.Base64
import run.cosy.akka.http.headers.HSCredentials
import run.cosy.http.auth.MessageSignature.SignatureVerifier
import run.cosy.http.auth.KeyIdAgent
import run.cosy.akka.http.headers.HSCredentials
import run.cosy.http.headers.{HttpSig, Rfc8941}
import run.cosy.http.{
  InvalidCreatedFieldException,
  InvalidExpiresFieldException,
  InvalidSigException
}

import java.net.URI
import java.security.{PrivateKey, PublicKey, Signature as JSignature}
import java.time.{Clock, Instant}
import java.util
import java.util.Locale
import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent
import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex

object HttpSigDirective:
   import run.cosy.http.headers.given
   import run.cosy.http.headers.SelectorOps
   import run.cosy.http.auth.KeyIdAgent
   import run.cosy.http.auth.AkkaHttpMessageSignature.*

   /** lifts a request context into an authentication directive.
     * @param reqc
     * @param fetch
     * @param _
     * @return
     */
   def httpSignature(reqc: RequestContext)(
       fetch: model.Uri => IO[SignatureVerifier[IO, KeyIdAgent]]
   )(using
       SelectorOps[HttpRequest],
       MonadError[IO, Throwable],
       cats.effect.unsafe.IORuntime,
       kernel.Clock[IO]
   ): AuthenticationDirective[KeyIdAgent] =
     authenticateOrRejectWithChallenge {
       case Some(HSCredentials(httpsig)) =>
         val agentIO: IO[KeyIdAgent] = reqc.request.signatureAuthN[IO, KeyIdAgent](
           keyIdtoUri.andThen(_.flatMap(fetch))
         )(httpsig)
         agentIO.map(a => AuthenticationResult.success(a)).unsafeToFuture()
       case _ => FastFuture.successful(
           AuthenticationResult.failWithChallenge(
             HttpChallenge("HttpSig", None, Map[String, String]())
           )
         )
     }

   def keyIdtoUri(keyId: Rfc8941.SfString): IO[model.Uri] = IO(model.Uri(keyId.asciiStr))
