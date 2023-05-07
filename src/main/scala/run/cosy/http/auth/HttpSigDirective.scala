/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http.auth

import akka.http.scaladsl.model
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.headers.*
import akka.http.scaladsl.server.AuthenticationFailedRejection.{
  CredentialsMissing,
  CredentialsRejected
}
import akka.http.scaladsl.server.Directives.{
  AsyncAuthenticator,
  AuthenticationResult,
  authenticateOrRejectWithChallenge,
  extract,
  extractCredentials,
  extractRequestContext,
  provide
}
import akka.http.scaladsl.server.RouteResult.Rejected
import akka.http.scaladsl.server.directives.BasicDirectives.extractExecutionContext
import akka.http.scaladsl.server.directives.RouteDirectives.reject
import akka.http.scaladsl.server.directives.{
  AuthenticationDirective,
  AuthenticationResult,
  Credentials
}
import akka.http.scaladsl.server.{AuthenticationFailedRejection, Directive1, RequestContext}
import akka.http.scaladsl.util.FastFuture
import cats.effect.unsafe.IORuntime
import cats.effect.{Async, IO, kernel}
import cats.{Monad, MonadError}
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.util.Base64
import run.cosy.akka.http.AkkaTp
import run.cosy.akka.http.AkkaTp.HT
import run.cosy.akka.http.headers.HSCredentials
import run.cosy.akka.http.messages.RequestSelectorFnsAkka
import run.cosy.http.auth.KeyIdAgent
import run.cosy.http.auth.MessageSignature.SignatureVerifier
import run.cosy.http.headers.{HttpSig, Rfc8941}
import run.cosy.http.messages.*
import run.cosy.http.{
  Http,
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
import scala.concurrent
import scala.concurrent.{ExecutionContext, Future, duration}
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

object HttpSigDirective:
   def keyIdtoUri(keyId: Rfc8941.SfString): IO[model.Uri] = IO(model.Uri(keyId.asciiStr))

class HttpSigDirective(
    sc: ServerContext,
    fetchKeyId: Uri => RequestContext => IO[MessageSignature.SignatureVerifier[IO, KeyIdAgent]]
)(using IOrun: cats.effect.unsafe.IORuntime):
   import run.cosy.http.auth.KeyIdAgent
   import run.cosy.http.headers.given
   given ASy: Async[IO]               = IO.asyncForIO
   given ReqFns[HT]                   = new RequestSelectorFnsAkka(using sc)
   val reqSelectors                   = new ReqSelectors[HT]
   val selectorDB: ReqComponentDB[HT] = ReqComponentDB(reqSelectors, HeaderIds.all)

   // we build a new SigVerifier for each request, as we I think we need to use the
   // context to make local calls correctly
   def sigV(reqCtx: RequestContext): SigVerifier[IO, KeyIdAgent, HT] =
      import AkkaTp.given
//     val msgSig: MessageSignature[HT] = new MessageSignature[HT]
      SigVerifier(
        selectorDB,
        HttpSigDirective.keyIdtoUri.andThen(_.flatMap(fetchKeyId(_)(reqCtx)))
      )

   import scala.language.implicitConversions

   /** lifts a request context into an authentication directive.
     *
     * @param reqc
     * @param fetch
     * @param _
     * @return
     */
   def httpSignature(reqc: RequestContext): AuthenticationDirective[KeyIdAgent] =
     authenticateOrRejectWithChallenge {
       case Some(HSCredentials(httpSig)) =>
         val aio =
           for
//   I think cachedRealTime would need a wider loop before unsafeToFuture is used...
//            nowIO <- ASy.cachedRealTime(duration.FiniteDuration(5, duration.SECONDS))
              now   <- ASy.realTime
              agent <- sigV(reqc)(reqc.request, now, httpSig)
           yield AuthenticationResult.success(agent)
         aio.unsafeToFuture()
       case x =>
         FastFuture.successful(
           AuthenticationResult.failWithChallenge(
             HttpChallenge("HttpSig", None, Map[String, String]())
           )
         )
     }
