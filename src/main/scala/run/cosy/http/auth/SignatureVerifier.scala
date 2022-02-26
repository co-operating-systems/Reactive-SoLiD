/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http.auth

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import run.cosy.http.InvalidSigException
import run.cosy.http.auth.KeyIdAgent
import run.cosy.http.headers.Rfc8941
import run.cosy.http.headers.Rfc8941.Bytes

import java.nio.charset.StandardCharsets
import java.security.{
  InvalidKeyException,
  PrivateKey,
  PublicKey,
  SignatureException,
  Signature as JSignature
}
import scala.util.{Failure, Success, Try}
import scala.util

object SignatureVerifier:
   // todo: move this to HttpSig package?

   /** Todo: integrate this with bobcats.
     *
     * this just returns a verification function on signatures and signing strings. Note that
     * JSignature objects are not thread safe: so this should be wrapped in some IO Monad, if one
     * were strict.
     */
   @throws[InvalidKeyException]
   @throws[SignatureException]
   def jsigVerifier(
       pubKey: PublicKey,
       sig: JSignature
   ): MessageSignature.SignatureVerifier[IO, Unit] =
     (signingStr, signature) =>
       IO {
         sig.initVerify(pubKey)
         sig.update(signingStr.toArray)
         val answer = sig.verify(signature.toArray)
         if answer then ()
         else throw InvalidSigException(s"cannot verify signature")
       }

   /** given a keyId Uri, a public key and a signature algorithm return a verifier that will return
     * an WebKeyIdAgent for verified signatures. The pubkey and algorithm must come from a request
     * to the keyId. So arguably those three arguments should be wrapped in one object constituting
     * a proof of them being fetched from that URI. Either that or the function should be useable
     * only from those contexts. One may want to model this even more strictly by having the
     * returned agent keep the full proof of the algorith (at least the name), as say different
     * strenght of hashes will give different levels of confidence in the idenity.
     *
     * We call this apply, because for the HttpSig framework all the time
     */
   def apply(
       keydId: Uri,
       pubKey: PublicKey,
       sigAlgorithm: JSignature
   ): MessageSignature.SignatureVerifier[IO, KeyIdAgent] =
     (signingStr, signature) =>
       jsigVerifier(pubKey, sigAlgorithm)(signingStr, signature)
         .map(_ => KeyIdAgent(keydId, pubKey))

end SignatureVerifier

/** Used for Signing bytes.
  */
case class SigningData(privateKey: PrivateKey, sig: JSignature):
   // this is not thread safe!
   def sign(bytes: Array[Byte]): Try[Array[Byte]] = Try {
     sig.initSign(privateKey)
     sig.update(bytes)
     sig.sign()
   }
