// https://github.com/typelevel/sbt-typelevel
addSbtPlugin("org.typelevel" % "sbt-typelevel" % "0.4.5")

//not sure if we need this https://github.com/sbt/sbt-buildinfo
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")

resolvers += Resolver.sonatypeRepo("snapshots")

// https://scalameta.org/scalafmt/docs/installation.html
// see https://oss.sonatype.org/content/repositories/snapshots/org/scalameta/sbt-scalafmt_2.12_1.0/
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.6+9-9da40876-SNAPSHOT")
