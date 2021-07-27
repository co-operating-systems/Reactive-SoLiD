import Dependencies._

val Scala3Version = "3.0.1"

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
		libraryDependencies ++= Scala213Libs.allCompatibleLibs,

		testFrameworks += new TestFramework("munit.Framework")
	)
