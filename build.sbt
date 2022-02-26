import Dependencies._

val Scala3Version = "3.1.1"
import Dependencies.{Scala213Libs => s213}

lazy val root = project
	.in(file("."))
	.settings(
		name := "cosy",
		description := "Reactive Solid",
		version := "0.1.3",
		scalaVersion := Scala3Version,

		//resolvers += Resolver.bintrayRepo("akka","snapshots"), //use if testing akka snapshots
		resolvers += Resolver.sonatypeRepo("snapshots"), //for banana-rdf

		libraryDependencies ++= Scala3Libs.all,
		libraryDependencies ++= JavaLibs.all,
		libraryDependencies ++= Seq(
			s213.akkaTyped.value,
			s213.akkaStream.value,
			s213.akkaSlf4j.value,
			s213.alpakka.value,
			s213.banana_rdf.value,
			s213.banana_rdf4j.value,
			s213.banana_jena.value
		),
		libraryDependencies ++= s213.akkaTest,
		libraryDependencies ++= Seq( // we use the bobcats test keys for http sig
			Scala3Libs.bobcats classifier "tests",
			Scala3Libs.bobcats classifier "tests-sources"
		),
		testFrameworks += new TestFramework("munit.Framework")
	)
