package run.cosy.http.auth

import akka.http.scaladsl.model.{DateTime, HttpEntity, HttpMessage, HttpMethods, HttpRequest, MediaTypes, Uri}
import akka.http.scaladsl.model.headers.{CacheDirectives, Date, GenericHttpCredentials, Host, RawHeader, `Cache-Control`}
import run.cosy.http.headers.{Rfc8941, SelectorOps, SigInput}
import run.cosy.http.headers.{HttpSig, SigInputs, Signature, Signatures, `Signature-Input`}
import Rfc8941.*
import Rfc8941.SyntaxHelper.*
import akka.http.scaladsl.util.FastFuture
import com.nimbusds.jose.JWSAlgorithm

import scala.concurrent.{Await, Future}
import run.cosy.http.headers.akka.{AkkaDictSelector, AkkaHeaderSelector, UntypedAkkaSelector}
import run.cosy.http.headers.akka.{`@request-target`, `cache-control`, `content-length`, `content-type`, `digest`, date, etag, host}
import run.cosy.http.headers.akka.date.collate
import run.cosy.http.auth.MessageSignature.*
import run.cosy.ldp.testUtils.StringUtils.*

import java.nio.charset.StandardCharsets
import java.security.{KeyFactory, MessageDigest, PrivateKey, PublicKey, Signature as JSignature}
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.time.Clock
import java.util.Base64
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import scala.language.implicitConversions

class TestMessageSigningRFCFn extends munit.FunSuite {

	// special headers used in the spec that we won't find elsewhere

	object `x-example` extends UntypedAkkaSelector:
		override val lowercaseName: String = "x-example"

	object `x-empty-header` extends UntypedAkkaSelector:
		override val lowercaseName: String = "x-empty-header"

	object `x-ows-header` extends UntypedAkkaSelector:
		override val lowercaseName: String = "x-ows-header"

	object `x-obs-fold-header` extends UntypedAkkaSelector:
		override val lowercaseName: String = "x-obs-fold-header"

	object `x-dictionary` extends AkkaDictSelector:
		override val lowercaseName: String = "x-dictionary"

	given specialSelectorOps: SelectorOps[HttpMessage] =
		run.cosy.http.headers.akka.akkaSelectorOps.append(
			`x-example`, `x-empty-header`,`x-ows-header`,`x-obs-fold-header`,`x-dictionary`
		)

	given ec: ExecutionContext = scala.concurrent.ExecutionContext.global
	given clock: Clock = Clock.fixed(java.time.Instant.ofEpochSecond(16188845000), java.time.ZoneOffset.UTC)

	def expectedHeader(name: String, value: String) = Success("\""+name+"\": "+value)

	test("2.1.2 Canoncicalized Examples") {
		val rfcCanonReq = HttpRequest(
			method = HttpMethods.GET,
			uri = Uri("/foo"),
			headers = Seq(
				Host("www.example.com"),
				Date(DateTime(2014, 06, 07, 20, 51, 35)),
				RawHeader("X-OWS-Header","   Leading and trailing whitespace.   "),
				RawHeader("X-Obs-Fold-Header","""Obsolete
														  |      line folding. """.stripMargin),
				RawHeader("X-Example",
					"""Example header
					  |   with some whitespace.  """.stripMargin),
				`Cache-Control`(CacheDirectives.`max-age`(60)),
				RawHeader("X-Empty-Header", ""),
				`Cache-Control`(CacheDirectives.`must-revalidate`)
			),
		)
		assertEquals(`cache-control`.signingString(rfcCanonReq),expectedHeader("cache-control","max-age=60, must-revalidate"))
		//note: It is Saturday, not Tuesday: either that is an error in the spec, or example of header with wrong date
		//   we fixed it here, because it is difficult to get broken dates past akka
		assertEquals(`date`.signingString(rfcCanonReq),expectedHeader("date","Sat, 07 Jun 2014 20:51:35 GMT"))
		assertEquals(`host`.signingString(rfcCanonReq),expectedHeader("host","www.example.com"))
		assertEquals(`x-empty-header`.signingString(rfcCanonReq),expectedHeader("x-empty-header",""))
		assertEquals(`x-obs-fold-header`.signingString(rfcCanonReq),expectedHeader("x-obs-fold-header","Obsolete line folding."))
		assertEquals(`x-ows-header`.signingString(rfcCanonReq),expectedHeader("x-ows-header","Leading and trailing whitespace."))
	}

	test("2.2. Dictionary Structured Field Members") {
		val rfcCanonReq = HttpRequest(
			headers = Seq(
				RawHeader("X-Dictionary"," a=1, b=2;x=1; y=2, c=(a  b   c) "),
			),
		)
		assertEquals(`x-dictionary`.signingString(rfcCanonReq,Token("a")),
			expectedHeader("x-dictionary","1"))
		assertEquals(`x-dictionary`.signingString(rfcCanonReq,Token("b")),
			expectedHeader("x-dictionary","2;x=1;y=2"))
		assertEquals(`x-dictionary`.signingString(rfcCanonReq,Token("c")),
			expectedHeader("x-dictionary","(a b c)"))
	}

	test("2.3. List Prefixes") {
		val rfcCanonReq = HttpRequest(
			headers = Seq(
				RawHeader("X-List-A"," ( a  b   c  d e   f ) "),
				RawHeader("X-List-B"," ( ) "),
			),
		)
	   //not yet implemented.
		// it feels like [[https://www.rfc-editor.org/rfc/rfc8941#name-lists RFC8941 §3.1 Lists]] may be
		// a better fit for what is intended here. Inner Lists on headers require there to be only 1 header of a given
		// name as multiple lists cannot be split. Or perhaps the same process would work there too?
		// Let us wait to see how the spec evolves.
	}

	test("2.4.1. Request Target") {
		import akka.http.scaladsl.model.HttpMethods._
		def rt(value: String): Success[String] = expectedHeader("@request-target",value)
		val req1 = HttpRequest(POST, Uri("/?param=value"),
			Seq(Host("www.example.com"))
		)
		assertEquals(
			`@request-target`.signingString(req1),
			rt("post /?param=value")
		)
		val req2 = HttpRequest(POST, Uri("/a/b"),
			Seq(Host("www.example.com"))
		)
		assertEquals(
			`@request-target`.signingString(req2),
			rt("post /a/b")
		)
		val req3 = HttpRequest(GET, Uri("http://www.example.com/a/"))
		assertEquals(
			`@request-target`.signingString(req3),
			rt("get /a/")
		)
		val req4 = HttpRequest(GET, Uri("http://www.example.com"))
		assertEquals(
			`@request-target`.signingString(req4),
			rt("get /")
		)
		//CONNECT: Not sure if this is allowed by Akka. It is not possible to create an HttpRequest with it
//		val req5 = HttpRequest(CONNECT, Uri("server.example.com:80",Uri.ParsingMode.Relaxed),
//			Seq(Host("www.example.com")))
//		assertEquals(
//			`@request-target`.signingString(req5),
//			rt("connect /")
//		)
		val req6 = HttpRequest(OPTIONS, Uri("*"),headers=Seq(Host("server.example.com")))
		assertEquals(
			`@request-target`.signingString(req6),
			rt("options *")
		)
	}

	test("2.5. Creating the Signature Input String") {
		/**
		 * [[https://www.ietf.org/archive/id/draft-ietf-httpbis-message-signatures-04.html#section-2.5 example request in §2.5]] **/
		val rfcSigInreq = HttpRequest(
			method = HttpMethods.GET,
			uri = Uri("/foo"),
			headers = Seq(
				Host("example.org"),
				Date(DateTime(2021, 04, 20, 02, 07, 55)),
				RawHeader("X-Example",
					"""Example header
					  |   with some whitespace.  """.stripMargin),
				`Cache-Control`(CacheDirectives.`max-age`(60)),
				RawHeader("X-Empty-Header", ""),
				`Cache-Control`(CacheDirectives.`must-revalidate`)
			),
			entity = HttpEntity(MediaTypes.`application/json`.toContentType, """{"hello": "world"}""")
		)
		val expectedSigInStr =
			""""@request-target": get /foo
			  |"host": example.org
			  |"date": Tue, 20 Apr 2021 02:07:55 GMT
			  |"cache-control": max-age=60, must-revalidate
			  |"x-empty-header": """.stripMargin+ //we make sure we have an extra space after the ':' here.
			//then we have a newline. todo: Is that right? Difficult to tell from the spec.
			"""
			  |"x-example": Example header with some whitespace.
			  |"@signature-params": ("@request-target" "host" "date" "cache-control" \
			  |  "x-empty-header" "x-example");created=1618884475;\
			  |  keyid="test-key-rsa-pss"""".rfc8792single

		val siginStr = """("@request-target" "host" "date" "cache-control" \
							  |  "x-empty-header" "x-example");created=1618884475;\
							  |  keyid="test-key-rsa-pss"""".rfc8792single

		val sigInIlist = IList(
			`@request-target`.sf, host.sf,date.sf,`cache-control`.sf,
			`x-empty-header`.sf,`x-example`.sf
		)(
			Token("created") -> SfInt(1618884475),
			Token("keyid") -> sf"test-key-rsa-pss"
		)
		assert(SigInput.valid(sigInIlist))

		val Some(sigInHandBuilt) = SigInput[HttpMessage](sigInIlist)
		val Some(sigin) = SigInput(siginStr)
		assertEquals(rfcSigInreq.signingString(sigin),Success(expectedSigInStr))
	}

	//
	// signature tests
	//
	import TestMessageSigningRFCFn._

	test("do the keys parse without throwing an exception?") {
		testKeyRSApub
		testKeyRSAPriv
		testKeyPSSpub
		testKeyPSSpriv
	}

	test("4.3 Multiple Signatures - spec test") {
		import java.util.Base64

		// let's test the [[https://github.com/httpwg/http-extensions/issues/1493#issuecomment-827103670 fix to version 04. of the spec]]
		val sigInputStr: String =
			""""signature";key="sig1": \
			|  :YlizxWySaL8RiCQOWpl/8TBLlinl/O9K5n+WCYladKkRfmZ4wdo42ikCrepkIoPd\
			|  csPIx5wYc53Kpq6PLmv3fRk/+BtFSJNdrfClMEg4kX8utYuMQhBHSLiEiDRjNSTWX\
			|  Wk8hwutEGijbna3FvBVzy1oa5tT08w/ffN7d/6dup1FWVt90KhK1Cx3RkQveMxAKC\
			|  3HH6Q26lAgJ54MyLqLrLXcjQKWhjWzkMkVjho4JLy87GTU6k4eIQxB4xDHbavbJKE\
			|  jS0Vlg2pqmcUkGVdL4zQ3NOOttIlKC1HL1SodXNd7UBM0C0R1GGqEi4Lsm9UKWuQP\
			|  vPFTW7qIgvjAthv/lA==:
			|"x-forwarded-for": 192.0.2.123
			|"@signature-params": ("signature";key="sig1" "x-forwarded-for")\
			|  ;created=1618884480;keyid="test-key-rsa";alg="rsa-v1_5-sha256"""".rfc8792single
		val proxySig: String =
			"""FSqkfwt17TLKqjjWrW9vf6a/r3329amAO3e7ByjkT60jjFTq4xdO74\
		  |    JTHrpz6DMSlQOKmhIiz8mq7T5SYOjfUZrKXpbP6jUFTStUa4HvNNvjhZc1jiHk9\
		  |    IhGGPPeOdRcTrzjDxSS+2l7G3nSpJ4t2LjtLnEPa1FIldgnJqwIa0SCiEPWFmnJ\
		  |    fTdc4VW2ngvYhuKUKFz/Jyx0GfmKQ4lAWxQwVtqRSURTscdY3VvxR+GpydqF8gQ\
		  |    U8iYslRHlRxBh+29cADAHVnT5j1iBkVdAfLS59xCYLnUc3UG7UfxU6kU1QgJ+2n\
		  |    A5NNQsxXeREcCFTe2FnjOy2atxG8bm+O8ZcA==""".rfc8792single

		val sigBytes = Base64.getDecoder.decode(proxySig)

		val javaSig = sha256rsaSig()
		javaSig.initVerify(testKeyRSApub)
		javaSig.update(sigInputStr.getBytes(StandardCharsets.US_ASCII))
		assert(javaSig.verify(sigBytes.toArray))
	}
	
	/** example from [[https://www.ietf.org/archive/id/draft-ietf-httpbis-message-signatures-04.html#section-b.2
	 * Appendix B.2.]] of the spec */
	val rfcAppdxB2Req = HttpRequest(method = HttpMethods.POST,
		uri = Uri("/foo?param=value&pet=dog"),
		headers = Seq(
			Host(Uri.Host("example.com"), 0),
			Date(DateTime(2021, 04, 20, 02, 07, 55)),
			RawHeader("Digest", "SHA-256=X48E9qOokqqrvdts8nOJRJN3OWDUoyWxBf7kbu9DBPE="),
		),
		entity = HttpEntity(MediaTypes.`application/json`.toContentType, """{"hello": "world"}""")
	)


	import run.cosy.http.headers.Rfc8941.Serialise.given
	import scala.concurrent.duration.given

	test("B.2.1. Minimal Signature Header using rsa-pss-sha512") {
		//1. build and test Input String
		val sigInputStrExpected =
			""""@signature-params": ();created=1618884475;keyid="test-key-rsa-pss"\
			  |  ;alg="rsa-pss-sha512"""".rfc8792single
		val Some(sigIn) = SigInput[HttpMessage](IList()(
			Token("created") -> SfInt(1618884475),
			Token("keyid") -> sf"test-key-rsa-pss",
			Token("alg") -> sf"rsa-pss-sha512"
		))
		assertEquals(rfcAppdxB2Req.signingString(sigIn), Success(sigInputStrExpected))

		//2. build and test signature
		val signatureStr =
			"""sig1=:qGKjr1213+iZCU1MCV8w2NTr/HvMGWYDzpqAWx7SrPE1y6gOkIQ3k2\
			  |  GlZDu9KnKnLN6LKX0JRa2M5vU9v/b0GjV0WSInMMKQJExJ/e9Y9K8q2eE0G9saGebEaWd\
			  |  R3Ao47odxLh95hBtejKIdiUBmQcQSAzAkoQ4aOZgvrHgkmvQDZQL0w30+8lMz3VglmN73\
			  |  CKp/ijZemO1iPdNwrdhAtDvj9OdFVJ/wiUECfU78aQWkQocvwrZXTmHCX9BMVUHGneXMY\
			  |  NQ0Y8umEHjxpnnLLvxUbw2KZrflp+l6m7WlhwXGJ15eAt1+mImanxUCtaKQJvEfcnOQ0S\
			  |  2jHysSRLheTA==:""".rfc8792single
		val sigs = Signature.parse(signatureStr)
		assertEquals(sigs.get.sigmap.canon,signatureStr)

		//3. build complete request header and test signature on it
		val specReq = rfcAppdxB2Req.addHeader(
			`Signature-Input`(SigInputs(Token("sig1"), sigIn)))
			.addHeader(Signature(sigs.get))
		val verifiedKeyId = Await.ready(
			specReq.signatureAuthN(keyidFetcher)(cred("sig1")),
			2.seconds
		)
// todo: this does not work. Is it the crypto algorith that is wrong or the example in the spec?
//		assertEquals(
//			verifiedKeyId.value,
//			Some(Success(run.cosy.http.auth.KeyidAgent("test-key-rsa-pss")))
//		)

		 //4. create our own signature and test that.
		//   note: RSASSA returns different signatures at different times. So we run it again
		//   Assuming rsa-pss-sha512 is the same as PS512 used by JWT

		val newReq = rfcAppdxB2Req.withSigInput(Token("sig1"), sigIn)
			.flatMap(_(`test-key-rsa-pss-sigdata`))

		val futureKeyId = newReq.get.signatureAuthN(keyidFetcher)(cred("sig1"))
		val keyIdReady = Await.ready(futureKeyId, 2.seconds)
		assertEquals(
			keyIdReady.value,
			Some(Success(run.cosy.http.auth.KeyidSubj("test-key-rsa-pss", testKeyPSSpub)))
		)
	}

	test("B.2.2 Header Coverage - Spec Test") {
		import java.util.Base64
		val sigInputStr =
			""""host": example.com
			  |"date": Tue, 20 Apr 2021 02:07:55 GMT
			  |"content-type": application/json
			  |"@signature-params": ("host" "date" "content-type");created=1618884475;keyid="test-key-rsa-pss"""".stripMargin
		val signature = "NtIKWuXjr4SBEXj97gbick4O95ff378I0CZOa2VnIeEXZ1itzAdqTp"+
			 "SvG91XYrq5CfxCmk8zz1Zg7ZGYD+ngJyVn805r73rh2eFCPO+ZXDs45Is/Ex8srzGC9sf"+
			"VZfqeEfApRFFe5yXDmANVUwzFWCEnGM6+SJVmWl1/jyEn45qA6Hw+ZDHbrbp6qvD4N0S9"+
			"2jlPyVVEh/SmCwnkeNiBgnbt+E0K5wCFNHPbo4X1Tj406W+bTtnKzaoKxBWKW8aIQ7rg9"+
			"2zqE1oqBRjqtRi5/Q6P5ZYYGGINKzNyV3UjZtxeZNnNJ+MAnWS0mofFqcZHVgSU/1wUzP"+
			"7MhzOKLca1Yg=="
		println(signature)
		val sigBytes = Base64.getDecoder.decode(signature)

		val javaSig = `rsa-pss-sha512`()
		javaSig.initVerify(testKeyPSSpub)
		javaSig.update(sigInputStr.getBytes(StandardCharsets.US_ASCII))
//todo:		assert(javaSig.verify(sigBytes.toArray))
	}

	test("B.2.2. Header Coverage") {
		//1. build and test Input String
		val sigParametersExpected =
			"""("host" "date" "content-type");created=1618884475\
			  |  ;keyid="test-key-rsa-pss"""".rfc8792single
		val Some(sigIn) = SigInput[HttpMessage](IList(host.sf,date.sf,`content-type`.sf)(
			Token("created") -> SfInt(1618884475),
			Token("keyid") -> sf"test-key-rsa-pss"))
		assertEquals(sigIn.canon, sigParametersExpected)

		val sigInputStrExpected =
			""""host": example.com
			  |"date": Tue, 20 Apr 2021 02:07:55 GMT
			  |"content-type": application/json
			  |"@signature-params": ("host" "date" "content-type");created=1618884475\
			  |  ;keyid="test-key-rsa-pss"""".rfc8792single
		assertEquals(rfcAppdxB2Req.signingString(sigIn),Success(sigInputStrExpected))

		//2. build and test signature
		val signatureStr =
			"""sig1=:NtIKWuXjr4SBEXj97gbick4O95ff378I0CZOa2VnIeEXZ1itzAdqTp\
			  |  SvG91XYrq5CfxCmk8zz1Zg7ZGYD+ngJyVn805r73rh2eFCPO+ZXDs45Is/Ex8srzGC9sf\
			  |  VZfqeEfApRFFe5yXDmANVUwzFWCEnGM6+SJVmWl1/jyEn45qA6Hw+ZDHbrbp6qvD4N0S9\
			  |  2jlPyVVEh/SmCwnkeNiBgnbt+E0K5wCFNHPbo4X1Tj406W+bTtnKzaoKxBWKW8aIQ7rg9\
			  |  2zqE1oqBRjqtRi5/Q6P5ZYYGGINKzNyV3UjZtxeZNnNJ+MAnWS0mofFqcZHVgSU/1wUzP\
			  |  7MhzOKLca1Yg==:""".rfc8792single
		val Success(sig1) = Signature.parse(signatureStr)
		assertEquals(sig1.sigmap.canon,signatureStr)

		//3. build complete request header and test signature on it
		val specReq = rfcAppdxB2Req.addHeader(
			`Signature-Input`(SigInputs(Token("sig1"), sigIn)))
			.addHeader(Signature(sig1))
		//verify that the new req still has the same signing string as expected
		assertEquals(specReq.signingString(sigIn),Success(sigInputStrExpected))

		println(specReq.documented)
		val verifiedKeyId = Await.ready(
			specReq.signatureAuthN(keyidFetcher)(cred("sig1")),
			2.seconds
		)
// todo: this does not work. Is it the crypto algorith that is wrong or the example in the spec?
//		assertEquals(
//			verifiedKeyId.value,
//			Some(Success(run.cosy.http.auth.KeyidAgent("test-key-rsa-pss",testKeyPSSpub)))
//		)

		//4. create our own signature and test that.
		//   note: RSASSA returns different signatures at different times. So we run it again
		//   Assuming rsa-pss-sha512 is the same as PS512 used by JWT

		val newReq = rfcAppdxB2Req.withSigInput(Token("sig1"), sigIn)
			.flatMap(_(`test-key-rsa-pss-sigdata`))

		val futureKeyId = newReq.get.signatureAuthN(keyidFetcher)(cred("sig1"))
		val keyIdReady = Await.ready(futureKeyId, 2.seconds)
		assertEquals(
			keyIdReady.value,
			Some(Success(run.cosy.http.auth.KeyidSubj("test-key-rsa-pss",testKeyPSSpub)))
		)
	}

	test("B.2.3. Full Coverage") {
		//1. build and test Input String
		val sigParametersExpected =
			"""("@request-target" "host" "date" "content-type" \
			  |  "digest" "content-length");created=1618884475\
			  |  ;keyid="test-key-rsa-pss"""".rfc8792single
		val Some(sigIn) = SigInput[HttpMessage](IList(
			`@request-target`.sf,host.sf,date.sf,`content-type`.sf,digest.sf,`content-length`.sf)(
			Token("created") -> SfInt(1618884475),
			Token("keyid") -> sf"test-key-rsa-pss"))
		assertEquals(sigIn.canon, sigParametersExpected)

		val sigInputStrExpected =
			""""@request-target": post /foo?param=value&pet=dog
			  |"host": example.com
			  |"date": Tue, 20 Apr 2021 02:07:55 GMT
			  |"content-type": application/json
			  |"digest": SHA-256=X48E9qOokqqrvdts8nOJRJN3OWDUoyWxBf7kbu9DBPE=
			  |"content-length": 18
			  |"@signature-params": ("@request-target" "host" "date" "content-type" \
			  |  "digest" "content-length");created=1618884475\
			  |  ;keyid="test-key-rsa-pss"""".rfc8792single
		assertEquals(rfcAppdxB2Req.signingString(sigIn),Success(sigInputStrExpected))

		//2. build and test signature
		val signatureStr =
			"""sig1=:QNPZtqAGWN1YMtsLJ1oyQMLg9TuIwjsIBESTo1/YXUsG+6Sl1uKUdT\
			  |  e9xswwrc3Ui3gUd4/tLv48NGih2TRDc1AWbEQDuy6pjroxSPtFjquubqzbszxit1arPNh\
			  |  ONnyR/8yuIh3bOXfc/NYJ3KLNaWR6MKrGinCYKTNwrX/0V67EMdSgd5HHnW5xHFgKfRCj\
			  |  rG3ncV+jbaeSPJ8e96RZgr8slcdwmqXdiwiIBCQDKRIQ3U2muJWvxyjV/IYhCTwAXJaUz\
			  |  sQPKzR5QWelXEVdHyv4WIB2lKaYh7mAsz0/ANxFYRRSp2Joms0OAnIAFX9kKCSp4p15/Q\
			  |  8L9vSIGNpQtw==:""".rfc8792single
		val Success(sig1) = Signature.parse(signatureStr)
		assertEquals(sig1.sigmap.canon,signatureStr)

		//3. build complete request header and test signature on it
		val specReq = rfcAppdxB2Req.addHeader(
			`Signature-Input`(SigInputs(Token("sig1"), sigIn)))
			.addHeader(Signature(sig1))
		val verifiedKeyId = Await.ready(
			specReq.signatureAuthN(keyidFetcher)(cred("sig1")),
			2.seconds
		)
		// todo: this does not work. Is it the crypto algorith that is wrong or the example in the spec?
//		assertEquals(
//			verifiedKeyId.value,
//			Some(Success(run.cosy.http.auth.KeyidAgent("test-key-rsa-pss")))
//		)

		//4. create our own signature and test that.
		//   note: RSASSA returns different signatures at different times. So we run it again
		//   Assuming rsa-pss-sha512 is the same as PS512 used by JWT

		val newReq = rfcAppdxB2Req.withSigInput(Token("sig1"), sigIn)
			.flatMap(_(`test-key-rsa-pss-sigdata`))

		val futureKeyId = newReq.get.signatureAuthN(keyidFetcher)(cred("sig1"))
		val keyIdReady = Await.ready(futureKeyId, 2.seconds)
		assertEquals(
			keyIdReady.value,
			Some(Success(run.cosy.http.auth.KeyidSubj("test-key-rsa-pss",testKeyPSSpub)))
		)
	}
}

object TestMessageSigningRFCFn {
	java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider)

	/**
	 * Public and Private keys from [[https://www.ietf.org/archive/id/draft-ietf-httpbis-message-signatures-04.html#section-b.1.1 Message Signatures §Appendix B.1.1]]
	 * Obviously, these should not be used other than for test cases!
	 * So place them here to make them available in other tests.
	 **/
	val testKeyRSApubStr: String =
		"""MIIBCgKCAQEAhAKYdtoeoy8zcAcR874L8cnZxKzAGwd7v36APp7Pv6Q2jdsPBRrw\
		  |WEBnez6d0UDKDwGbc6nxfEXAy5mbhgajzrw3MOEt8uA5txSKobBpKDeBLOsdJKFq\
		  |MGmXCQvEG7YemcxDTRPxAleIAgYYRjTSd/QBwVW9OwNFhekro3RtlinV0a75jfZg\
		  |kne/YiktSvLG34lw2zqXBDTC5NHROUqGTlML4PlNZS5Ri2U4aCNx2rUPRcKIlE0P\
		  |uKxI4T+HIaFpv8+rdV6eUgOrB2xeI1dSFFn/nnv5OoZJEIB+VmuKn3DCUcCZSFlQ\
		  |PSXSfBDiUGhwOw76WuSSsf1D4b/vLoJ10wIDAQAB""".rfc8792single
	val testKeyRSAprivStr: String =
		"""MIIEqAIBAAKCAQEAhAKYdtoeoy8zcAcR874L8cnZxKzAGwd7v36APp7Pv6Q2jdsP\
		  |BRrwWEBnez6d0UDKDwGbc6nxfEXAy5mbhgajzrw3MOEt8uA5txSKobBpKDeBLOsd\
		  |JKFqMGmXCQvEG7YemcxDTRPxAleIAgYYRjTSd/QBwVW9OwNFhekro3RtlinV0a75\
		  |jfZgkne/YiktSvLG34lw2zqXBDTC5NHROUqGTlML4PlNZS5Ri2U4aCNx2rUPRcKI\
		  |lE0PuKxI4T+HIaFpv8+rdV6eUgOrB2xeI1dSFFn/nnv5OoZJEIB+VmuKn3DCUcCZ\
		  |SFlQPSXSfBDiUGhwOw76WuSSsf1D4b/vLoJ10wIDAQABAoIBAG/JZuSWdoVHbi56\
		  |vjgCgkjg3lkO1KrO3nrdm6nrgA9P9qaPjxuKoWaKO1cBQlE1pSWp/cKncYgD5WxE\
		  |CpAnRUXG2pG4zdkzCYzAh1i+c34L6oZoHsirK6oNcEnHveydfzJL5934egm6p8DW\
		  |+m1RQ70yUt4uRc0YSor+q1LGJvGQHReF0WmJBZHrhz5e63Pq7lE0gIwuBqL8SMaA\
		  |yRXtK+JGxZpImTq+NHvEWWCu09SCq0r838ceQI55SvzmTkwqtC+8AT2zFviMZkKR\
		  |Qo6SPsrqItxZWRty2izawTF0Bf5S2VAx7O+6t3wBsQ1sLptoSgX3QblELY5asI0J\
		  |YFz7LJECgYkAsqeUJmqXE3LP8tYoIjMIAKiTm9o6psPlc8CrLI9CH0UbuaA2JCOM\
		  |cCNq8SyYbTqgnWlB9ZfcAm/cFpA8tYci9m5vYK8HNxQr+8FS3Qo8N9RJ8d0U5Csw\
		  |DzMYfRghAfUGwmlWj5hp1pQzAuhwbOXFtxKHVsMPhz1IBtF9Y8jvgqgYHLbmyiu1\
		  |mwJ5AL0pYF0G7x81prlARURwHo0Yf52kEw1dxpx+JXER7hQRWQki5/NsUEtv+8RT\
		  |qn2m6qte5DXLyn83b1qRscSdnCCwKtKWUug5q2ZbwVOCJCtmRwmnP131lWRYfj67\
		  |B/xJ1ZA6X3GEf4sNReNAtaucPEelgR2nsN0gKQKBiGoqHWbK1qYvBxX2X3kbPDkv\
		  |9C+celgZd2PW7aGYLCHq7nPbmfDV0yHcWjOhXZ8jRMjmANVR/eLQ2EfsRLdW69bn\
		  |f3ZD7JS1fwGnO3exGmHO3HZG+6AvberKYVYNHahNFEw5TsAcQWDLRpkGybBcxqZo\
		  |81YCqlqidwfeO5YtlO7etx1xLyqa2NsCeG9A86UjG+aeNnXEIDk1PDK+EuiThIUa\
		  |/2IxKzJKWl1BKr2d4xAfR0ZnEYuRrbeDQYgTImOlfW6/GuYIxKYgEKCFHFqJATAG\
		  |IxHrq1PDOiSwXd2GmVVYyEmhZnbcp8CxaEMQoevxAta0ssMK3w6UsDtvUvYvF22m\
		  |qQKBiD5GwESzsFPy3Ga0MvZpn3D6EJQLgsnrtUPZx+z2Ep2x0xc5orneB5fGyF1P\
		  |WtP+fG5Q6Dpdz3LRfm+KwBCWFKQjg7uTxcjerhBWEYPmEMKYwTJF5PBG9/ddvHLQ\
		  |EQeNC8fHGg4UXU8mhHnSBt3EA10qQJfRDs15M38eG2cYwB1PZpDHScDnDA0=""".rfc8792single
	val testKeyPSSPubStr: String =
		"""MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAr4tmm3r20Wd/PbqvP1s2\
		  |+QEtvpuRaV8Yq40gjUR8y2Rjxa6dpG2GXHbPfvMs8ct+Lh1GH45x28Rw3Ry53mm+\
		  |oAXjyQ86OnDkZ5N8lYbggD4O3w6M6pAvLkhk95AndTrifbIFPNU8PPMO7OyrFAHq\
		  |gDsznjPFmTOtCEcN2Z1FpWgchwuYLPL+Wokqltd11nqqzi+bJ9cvSKADYdUAAN5W\
		  |Utzdpiy6LbTgSxP7ociU4Tn0g5I6aDZJ7A8Lzo0KSyZYoA485mqcO0GVAdVw9lq4\
		  |aOT9v6d+nb4bnNkQVklLQ3fVAvJm+xdDOp9LCNCN48V2pnDOkFV6+U9nV5oyc6XI\
		  |2wIDAQAB""".rfc8792single
	val testKeyPSSPrivStr: String =
		"""MIIEvgIBADALBgkqhkiG9w0BAQoEggSqMIIEpgIBAAKCAQEAr4tmm3r20Wd/Pbqv\
		  |P1s2+QEtvpuRaV8Yq40gjUR8y2Rjxa6dpG2GXHbPfvMs8ct+Lh1GH45x28Rw3Ry5\
		  |3mm+oAXjyQ86OnDkZ5N8lYbggD4O3w6M6pAvLkhk95AndTrifbIFPNU8PPMO7Oyr\
		  |FAHqgDsznjPFmTOtCEcN2Z1FpWgchwuYLPL+Wokqltd11nqqzi+bJ9cvSKADYdUA\
		  |AN5WUtzdpiy6LbTgSxP7ociU4Tn0g5I6aDZJ7A8Lzo0KSyZYoA485mqcO0GVAdVw\
		  |9lq4aOT9v6d+nb4bnNkQVklLQ3fVAvJm+xdDOp9LCNCN48V2pnDOkFV6+U9nV5oy\
		  |c6XI2wIDAQABAoIBAQCUB8ip+kJiiZVKF8AqfB/aUP0jTAqOQewK1kKJ/iQCXBCq\
		  |pbo360gvdt05H5VZ/RDVkEgO2k73VSsbulqezKs8RFs2tEmU+JgTI9MeQJPWcP6X\
		  |aKy6LIYs0E2cWgp8GADgoBs8llBq0UhX0KffglIeek3n7Z6Gt4YFge2TAcW2WbN4\
		  |XfK7lupFyo6HHyWRiYHMMARQXLJeOSdTn5aMBP0PO4bQyk5ORxTUSeOciPJUFktQ\
		  |HkvGbym7KryEfwH8Tks0L7WhzyP60PL3xS9FNOJi9m+zztwYIXGDQuKM2GDsITeD\
		  |2mI2oHoPMyAD0wdI7BwSVW18p1h+jgfc4dlexKYRAoGBAOVfuiEiOchGghV5vn5N\
		  |RDNscAFnpHj1QgMr6/UG05RTgmcLfVsI1I4bSkbrIuVKviGGf7atlkROALOG/xRx\
		  |DLadgBEeNyHL5lz6ihQaFJLVQ0u3U4SB67J0YtVO3R6lXcIjBDHuY8SjYJ7Ci6Z6\
		  |vuDcoaEujnlrtUhaMxvSfcUJAoGBAMPsCHXte1uWNAqYad2WdLjPDlKtQJK1diCm\
		  |rqmB2g8QE99hDOHItjDBEdpyFBKOIP+NpVtM2KLhRajjcL9Ph8jrID6XUqikQuVi\
		  |4J9FV2m42jXMuioTT13idAILanYg8D3idvy/3isDVkON0X3UAVKrgMEne0hJpkPL\
		  |FYqgetvDAoGBAKLQ6JZMbSe0pPIJkSamQhsehgL5Rs51iX4m1z7+sYFAJfhvN3Q/\
		  |OGIHDRp6HjMUcxHpHw7U+S1TETxePwKLnLKj6hw8jnX2/nZRgWHzgVcY+sPsReRx\
		  |NJVf+Cfh6yOtznfX00p+JWOXdSY8glSSHJwRAMog+hFGW1AYdt7w80XBAoGBAImR\
		  |NUugqapgaEA8TrFxkJmngXYaAqpA0iYRA7kv3S4QavPBUGtFJHBNULzitydkNtVZ\
		  |3w6hgce0h9YThTo/nKc+OZDZbgfN9s7cQ75x0PQCAO4fx2P91Q+mDzDUVTeG30mE\
		  |t2m3S0dGe47JiJxifV9P3wNBNrZGSIF3mrORBVNDAoGBAI0QKn2Iv7Sgo4T/XjND\
		  |dl2kZTXqGAk8dOhpUiw/HdM3OGWbhHj2NdCzBliOmPyQtAr770GITWvbAI+IRYyF\
		  |S7Fnk6ZVVVHsxjtaHy1uJGFlaZzKR4AGNaUTOJMs6NadzCmGPAxNQQOCqoUjn4XR\
		  |rOjr9w349JooGXhOxbu8nOxX""".rfc8792single

	lazy val testKeyRSApub: PublicKey = {
		java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider)
		import java.math.BigInteger
		import java.security.spec.RSAPublicKeySpec
		val pkcs1PublicKey = org.bouncycastle.asn1.pkcs.RSAPublicKey.getInstance(testKeyRSApubStr.base64Decode.unsafeArray)
		val modulus = pkcs1PublicKey.getModulus
		val publicExponent = pkcs1PublicKey.getPublicExponent
		val keySpec = new RSAPublicKeySpec(modulus, publicExponent)
		KeyFactory.getInstance("RSA").generatePublic(keySpec)
	}
	lazy val testKeyRSAPriv: PrivateKey = {
		KeyFactory.getInstance("RSA")
			.generatePrivate(new PKCS8EncodedKeySpec(testKeyRSAprivStr.base64Decode.unsafeArray))
	}
	lazy val testKeyPSSpub = KeyFactory.getInstance("RSA")
		.generatePublic(new X509EncodedKeySpec(testKeyPSSPubStr.base64Decode.unsafeArray))
	lazy val testKeyPSSpriv = KeyFactory.getInstance("RSASSA-PSS")
		.generatePrivate(new PKCS8EncodedKeySpec(testKeyPSSPrivStr.base64Decode.unsafeArray))

	/** one always has to create a new signature on each verification if in multi-threaded environement */
	def `rsa-pss-sha512`(): JSignature =
//	   also tried see [[https://tools.ietf.org/html/rfc7518 JSON Web Algorithms (JWA) RFC]]
//		com.nimbusds.jose.crypto.implRSASSA.getSignerAndVerifier(JWSAlgorithm.PS512, new BouncyCastleProvider() )
		val rsapss = JSignature.getInstance("RSASSA-PSS")
		import java.security.spec.{PSSParameterSpec, MGF1ParameterSpec}
		val pssSpec = new PSSParameterSpec(
			"SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 512 / 8, 1)
		println("PSSParameterSpec="+pssSpec)
		rsapss.setParameter(pssSpec)
		rsapss

	//todo: remove dependence on JW2JCA
	def sha256rsaSig(): JSignature = JW2JCA.getSignerAndVerifier("SHA256withRSA").get

	/** Sigdata should always be new too in multithreaded environements, as it uses stateful signatures. */
	def `test-key-rsa-pss-sigdata`: SigningData = SigningData(testKeyPSSpriv, `rsa-pss-sha512`())
	def `test-key-rsa-sigdata`: SigningData = SigningData(testKeyRSAPriv, sha256rsaSig())

	/**
	 * emulate fetching the signature verification info for the keyids given in the Spec
	 * */
	def keyidFetcher(keyid: Rfc8941.SfString): Future[SignatureVerifier[KeyidSubj]] =
		import run.cosy.http.auth.SignatureVerifier.keyidVerifier
		keyid.asciiStr match
			case "test-key-rsa-pss" =>
				FastFuture.successful(keyidVerifier(keyid, testKeyPSSpub, `rsa-pss-sha512`()))
			case "test-key-rsa" =>
				FastFuture.successful(keyidVerifier(keyid,testKeyRSApub, sha256rsaSig()))
			case x => FastFuture.failed(new Throwable(s"can't get info on sig $x"))


	/** we are using [[https://github.com/solid/authentication-panel/blob/main/proposals/HttpSignature.md HttpSig]]
	 * extension to verify Signing HTTP Messages */
	def cred(signame: String) = HttpSig(Rfc8941.Token(signame))


}
