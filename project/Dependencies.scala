import sbt.{CrossVersion, _}

/**
 * https://www.scala-sbt.org/1.x/docs/Organizing-Build.html
 */
object Dependencies {

	object Versions {
		val Akka               = "2.6.15"
		val AkkaHttp           = "10.2.5"
		val scalaz             = "7.4.0-M7"
		val circe              = "0.14.0-M4"
		val banana             = "0.8.6-SNAPSHOT"
		val alpakka            = "2.0.2"
		val bouncy             = "1.68"
		val refined            = "0.9.23+7-d4890dd0-SNAPSHOT"
		val catsParse          = "0.3.4"
		val catsCore           = "2.6.1"
		val catsFree           = catsCore
		val munit 				  = "0.7.28"
		val disciplineMunit    = "1.0.9"
		val catsEffect         = "3.1.1"
		val scalatest          = "3.2.9"
		val titaniumJSonLD     = "1.0.0"
		val nimbusDS           = "9.9"
		val logback            = "1.2.3"
		val apacheCommonsCodec = "1.15"
		val izumiReflect       = "1.1.3-RC1"
	}

	//
	// scala 2.13 libs
	//

	object Scala213Libs {

		import Dependencies.{Versions => V}

		/**
		 * Akka Http Core
		 * Apache 2 License
		 *
		 * @see https://akka.io
		 * @see https://repo1.maven.org/maven2/com/typesafe/akka
		 * */
		val akka = Seq("com.typesafe.akka" % "akka-actor-typed" % V.Akka,
			"com.typesafe.akka" % "akka-stream" % V.Akka,
			"com.typesafe.akka" % "akka-http" % V.AkkaHttp,
			"com.typesafe.akka" % "akka-slf4j" % V.Akka)

		/**
		 * Apache 2 License
		 *
		 * @see https://doc.akka.io/docs/alpakka/current/
		 */
		val alpakka = "com.lightbend.akka" % "akka-stream-alpakka-file" % V.alpakka


		val akkaTest = Seq("com.typesafe.akka" % "akka-actor-testkit-typed" % V.Akka % Test,
			"com.typesafe.akka" % "akka-stream-testkit" % V.Akka % Test,
			"com.typesafe.akka" % "akka-http-testkit" % V.AkkaHttp % Test)

		/**
		 * banana-rdf uses Scalaz so we won't use cats right now.
		 * There is a 3.0.0 version of scalaz out, but until banana-rdf compiles to 3.0.0 we
		 * need to use the 2.13 scala version
		 *
		 * @see https://scalaz.github.io/7/
		 * @see [[https://github.com/scalaz/scalaz/blob/master/LICENSE.txt License]]
		 */
		val scalaz = "org.scalaz" % "scalaz-core" % V.scalaz

		/**
		 * banana-rdf is still using 2.13
		 * [[https://github.com/banana-rdf/banana-rdf/blob/series/0.8.x/LICENSE.md W3C License]]
		 *
		 * @see https://github.com/banana-rdf/banana-rdf
		 */
		val banana = Seq(
			"net.bblfish.rdf" % "banana-rdf" % V.banana,
			"net.bblfish.rdf" % "banana-jena" % V.banana,
			"net.bblfish.rdf" %% "banana-rdf4j" % V.banana
		)


		//	val refined = Seq(
		//		"eu.timepit" %% "refined"                 % refinedVersion,
		//		"eu.timepit" %% "refined-cats"            % refinedVersion // optional
		//	).map(_.exclude("org.scala-lang.modules","scala-xml_2.13"))

		def allCompatibleLibs = (Seq(alpakka) ++ akka ++ akkaTest ++ banana)
			.map(o => o cross CrossVersion.for3Use2_13)
	}

	//
	// Scala 3 libs
	//
	object Scala3Libs {

		import Dependencies.{Versions => V}

		/**
		 * [[https://www.scalatest.org/install home page]] published under Apache 2 License
		 *
		 * @see [[https://mvnrepository.com/artifact/org.scalatest/scalatest maven]]
		 * */
		val scalatest = "org.scalatest" %% "scalatest" % V.scalatest % Test

		/**
		 * MIT License
		 *
		 * @see https://github.com/typelevel/cats-parse
		 * @see https://mvnrepository.com/artifact/org.typelevel/cats-parse
		 */
		val catsParse = "org.typelevel" %% "cats-parse" % V.catsParse

		/**
		 * MIT License
		 *
		 * @see https://mvnrepository.com/artifact/org.typelevel/cats-core
		 * @see https://github.com/typelevel/cats
		 * */
		val catsCore = "org.typelevel" %% "cats-core" % V.catsCore
		
		/**
		 * MIT License
		 *
		 * @see https://mvnrepository.com/artifact/org.typelevel/cats-laws
		 * @see https://github.com/typelevel/cats
		 * */
		val catsLaws = "org.typelevel" %% "cats-laws" % V.catsCore % Test
		
		/**
		 * MIT License
		 *
		 * @see https://mvnrepository.com/artifact/org.typelevel/cats-effect
		 * @see https://github.com/typelevel/cats-effect
		 * */
		val catsEffect = "org.typelevel" %% "cats-effect" % V.catsEffect

		/**
		 * MIT License
		 *
		 * @see https://mvnrepository.com/artifact/org.typelevel/cats-free
		 * @see https://github.com/typelevel/cats-free
		 * */
		val catsFree = "org.typelevel" %% "cats-free" % V.catsFree
		
		/**
		 * MIT License
		 *
		 * @see https://mvnrepository.com/artifact/org.typelevel/alleycats-core
		 * @see https://github.com/typelevel/cats
		 * */
		val alleyCats = "org.typelevel" %% "alleycats-core" % V.catsCore
		
		val izumiReflect = "dev.zio" %% "izumi-reflect" % V.izumiReflect

		/**
		 * BSD-3 Clause License https://opensource.org/licenses/BSD-3-Clause
		 *
		 * @see [[https://scalameta.org/munit/docs/getting-started.html Getting Started]]
		 * @see https://mvnrepository.com/artifact/org.scalameta/munit
		 */
		val munit = "org.scalameta" %% "munit" % V.munit % Test
		
		/**
		 * Apache 2 License
		 *
		 * @see [[https://scalameta.org/munit/docs/getting-started.html Getting Started]]
		 * @see https://mvnrepository.com/artifact/org.scalameta/munit
		 */
		val disciplineMunit = "org.typelevel" %% "discipline-munit" % V.disciplineMunit % Test
		
		val all = Seq(
			catsParse, catsCore, catsFree, alleyCats, catsLaws,
			izumiReflect,
			scalatest, munit, disciplineMunit)
	}

	//
	// Java Libs
	//
	object JavaLibs {

		import Dependencies.{Versions => V}

		/**
		 * Apache 2 License
		 * Titanium JSON-LD 1.1 parser. Only Java parser able to parse security vocab files it seems.
		 * Should be integrated into banana-rdf.
		 *
		 * @see https://github.com/filip26/titanium-json-ld
		 */
		val titaniumJSonLD = "com.apicatalog" % "titanium-json-ld" % V.titaniumJSonLD

		/**
		 * Apache 2 License
		 *
		 * @see https://connect2id.com/products/nimbus-jose-jwt/examples/jwk-conversion
		 */
		val nimbusDS = "com.nimbusds" % "nimbus-jose-jwt" % V.nimbusDS

		/**
		 * BouncyCastle (for parsing PEM encoded objects at present in test)
		 * MIT style License
		 *
		 * @see https://www.bouncycastle.org/latest_releases.html
		 * @see https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk15to18/
		 */
		val bouncy = Seq(
			//"org.bouncycastle" % "bcprov-jdk15to18" % bouncyVersion,
			//"org.bouncycastle" % "bctls-jdk15to18" % bouncyVersion,
			"org.bouncycastle" % "bcpkix-jdk15to18" % V.bouncy % Test
		)


		/**
		 * License [[http://logback.qos.ch/license.html EPL v1.0 and the LGPL 2.1]]
		 * used by Akka logging
		 *
		 * @see http://logback.qos.ch/download.html
		 */
		val logback = "ch.qos.logback" % "logback-classic" % V.logback

		/**
		 * Apache 2.0
		 *
		 * @see [[https://mvnrepository.com/artifact/commons-codec/commons-codec Apache Commons Codec]]
		 */
		val apacheCommonsCodec = "commons-codec" % "commons-codec" % V.apacheCommonsCodec

		val all = Seq(titaniumJSonLD, nimbusDS, logback, apacheCommonsCodec) ++ bouncy
	}

}

//libraries that could be used later

// https://circe.github.io/circe/
//		libraryDependencies ++= Seq(
//			"io.circe" %% "circe-core",
//			"io.circe" %% "circe-generic",
//			"io.circe" %% "circe-parser"
//		).map(_ % circeVersion),

//	// https://connect2id.com/products/nimbus-jose-jwt/examples/jwk-conversion
//			"com.nimbusds" % "nimbus-jose-jwt" % "9.7",
//			"org.glassfish" % "jakarta.json" % "2.0.0",

//https://mvnrepository.com/artifact/com.lihaoyi/utest
//https://github.com/lihaoyi/utest
//libraryDependencies += "com.lihaoyi" %% "utest" % "0.7.7" % "test",
// testFrameworks += new TestFramework("utest.runner.Framework")
