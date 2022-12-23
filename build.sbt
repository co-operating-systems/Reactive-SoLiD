import Dependencies._

val Scala3Version = "3.2.1"

name               := "Reactive Solid"
organizationName   := "Henry Story"
headerLicenseStyle := HeaderLicenseStyle.SpdxSyntax

ThisBuild / tlBaseVersion          := "0.3"
ThisBuild / tlUntaggedAreSnapshots := true
ThisBuild / organization           := "net.bblfish.solid"
ThisBuild / organizationName       := "Henry Story"

ThisBuild / developers := List(
  tlGitHubDev("bblfish", "Henry Story")
)
ThisBuild / startYear := Some(2021)

ThisBuild / tlCiReleaseBranches := Seq() // "scala3" if github were to do the releases
ThisBuild / tlCiReleaseTags     := false // don't publish artifacts on github

ThisBuild / crossScalaVersions         := Seq(Scala3Version)
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
ThisBuild / homepage := Some(url("https://github.com/co-operating-systems/Reactive-SoLiD"))
ThisBuild / scmInfo := Some(ScmInfo(
  url("https://github.com/co-operating-systems/Reactive-SoLiD"),
  "git@github.com:co-operating-systems/Reactive-SoLiD.git"
))

//may want to remove for sbt-tylevel 0.5 - added this to avoid having all tests run for pull and push in CI
//see https://github.com/typelevel/sbt-typelevel/issues/177
ThisBuild / githubWorkflowTargetBranches := Seq("main")

// does not work for some reason
//ThisBuild / headerLicense := Some(HeaderLicense.ALv2(
//  "2021",
//  "Henry Story",
//  HeaderLicenseStyle.SpdxSyntax
//))

import Dependencies.{Scala213Libs => s213}

lazy val root = project
  .in(file("."))
  .settings(
    description  := "Reactive Solid Web Server",
    scalaVersion := Scala3Version,

    // resolvers += Resolver.bintrayRepo("akka","snapshots"), //use if testing akka snapshots
    resolvers += Resolver.sonatypeRepo("snapshots"), // for banana-rdf

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
