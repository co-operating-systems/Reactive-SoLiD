package run.cosy.http.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.factories.DefaultJWSSignerFactory
import com.nimbusds.jose.crypto.impl.RSASSA
import com.nimbusds.jose.jwk.{AsymmetricJWK, ECKey, JWK, RSAKey}
import com.nimbusds.jose.util.Base64
import run.cosy.http.CryptoException
import run.cosy.http.auth.MessageSignature

import java.security.{PrivateKey, Provider, PublicKey, Signature}
import scala.util.{Failure, Try}
import akka.http.scaladsl.model.Uri
import cats.effect.IO
import run.cosy.http.auth.KeyIdAgent


/**
 * Map JWK Names to Java Cryptography Architecture names
 */
object JW2JCA {
	val signerFactory = new DefaultJWSSignerFactory()
	import run.cosy.http.auth.SignatureVerifier

	def jw2rca(jwk: JWK, keyId: Uri): Try[MessageSignature.SignatureVerifier[IO,KeyIdAgent]] =
		jwk.getAlgorithm match
		case jwsAlgo: JWSAlgorithm =>
			Try(RSASSA.getSignerAndVerifier(jwsAlgo,signerFactory.getJCAContext.getProvider))
				.flatMap{ sig =>
					jwk match
					case k: AsymmetricJWK => Try(
						SignatureVerifier(keyId,k.toPublicKey, sig)
					)
					case _ => Failure(CryptoException("we only use asymmetric keys!"))
				}
		case alg => Failure(CryptoException("We do not support algorithm "+alg))

	/**
	 * Get the java.security.signature for a given JCA Algorithm
	 * todo: build a typesafe library of such algorithms
	 */
	def getSignerAndVerifier(jcaAlg: String, providerOpt: Option[Provider]=None): Try[Signature] =
		Try {
			providerOpt.map(provider => Signature.getInstance(jcaAlg, provider))
				.getOrElse(Signature.getInstance(jcaAlg))
		}


}

