/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.http.javadsl.model.headers.HttpCredentials
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.HttpHeader.ParsingResult.Ok
import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.MediaTypes.`text/plain`
import akka.http.scaladsl.model.StatusCodes.*
import akka.http.scaladsl.model.headers.*
import akka.http.scaladsl.model.headers.LinkParams.rel
import akka.http.scaladsl.server.AuthenticationFailedRejection
import akka.http.scaladsl.server.AuthenticationFailedRejection.CredentialsRejected
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.util.Timeout
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import run.cosy.RDF.*
import run.cosy.RDF.Prefix.wac
import run.cosy.RDF.ops.*
import run.cosy.http.RDFMediaTypes.`text/turtle`
import run.cosy.http.auth.*
import run.cosy.http.headers.Slug
import run.cosy.http.util.UriX.*
import run.cosy.http.{RDFMediaTypes, RdfParser}
import run.cosy.ldp.ACInfo.*
import run.cosy.ldp.TestSolidRouteSpec.{AccessControl, aclEffectiveLinkHeaders, aclLinkHeaders}
import run.cosy.ldp.fs.BasicContainer
import run.cosy.ldp.testUtils.TmpDir.{createDir, deleteDir}
import run.cosy.{Solid, SolidTest}

import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt

object TestSolidRouteSpec:
   import akka.http.scaladsl.model.headers.LinkParams.*
   import org.scalatest.matchers.should.Matchers.*
   val AccessControl = wac.accessControl.getString

   val rootUri: Uri = Uri("http://localhost:8080")
   val ServerData   = BasicACLTestServer(rootUri)

   val relativeRootACLGr: Rdf#Graph = ServerData.rootACL._2
   val rootACLGr                    = ServerData.absDB(ServerData.rootACL._1)
   val owner                        = rootUri.withPath(Uri.Path("/owner")).withFragment("i")

   def aclLinkHeaders(links: Seq[Link]): Seq[Uri] = links.map(
     _.values.collect {
       case lv @ LinkValue(uri, params) if params.exists {
             case rel("acl")         => true
             case rel(AccessControl) => true
             case _                  => false
           } => uri
     }
   ).flatten

   val EffectiveLink = wac.accessControl.toAkka.withScheme("https").toString()
   def aclEffectiveLinkHeaders(links: Seq[Link]): Seq[Uri] = links.map(
     _.values.collect {
       case lv @ LinkValue(uri, params) if params.exists {
             case rel(EffectiveLink) => true
             case _                  => false
           } => uri
     }
   ).flatten

end TestSolidRouteSpec

class TestSolidRouteSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

   import TestSolidRouteSpec.*
   import akka.http.scaladsl.client.RequestBuilding as Req
   import akka.http.scaladsl.server.Directives

   val dirPath: Path = createDir("solidTest_")

   // This test does not use the classic APIs,
   // so it needs to adapt the system:

   import akka.actor.typed.scaladsl.adapter.*

   given typedSystem: ActorSystem[Nothing] = system.toTyped
   given registry: SolidPostOffice         = SolidPostOffice(typedSystem)

   given timeout: Timeout     = Timeout(5000.milliseconds)
   given scheduler: Scheduler = typedSystem.scheduler

   import akka.http.scaladsl.model.headers

   def toUri(path: String): Uri = rootUri.withPath(Uri.Path(path))

   def withServer(test: Solid => Any): Unit =
      val testKit = ActorTestKit()
      val rootCntr: Behavior[BasicContainer.AcceptMsg] = BasicContainer(
        rootUri, dirPath,
        Root(ServerData.rootACL._1)
      )
      given sys: ActorSystem[?]                        = testKit.internalSystem
      val rootActr: ActorRef[BasicContainer.AcceptMsg] = testKit.spawn(rootCntr, "solid")
      SolidPostOffice(sys).addRoot(rootUri, rootActr)
      val solid = new Solid(rootUri, dirPath)
      try
        test(solid)
      finally testKit.shutdownTestKit()
   end withServer

   override def afterAll(): Unit = deleteDir(dirPath)

   "The Server" when {
     val rootC   = toUri("/")
     val rootACL = toUri("/.acl")

     "started for the first time. All actions done as WebServerAgent" in withServer { solid =>
        info(s"we GET <$rootUri>")
        Req.Get(rootUri).withHeaders(Accept(`*/*`))
          ~> solid.routeLdp(WebServerAgent)
          ~> check {
            status shouldEqual MovedPermanently
            header[Location].get shouldEqual Location(rootC)
          }

        Req.Get(rootUri.withPath(Uri.Path./)).withHeaders(Accept(`*/*`))
          ~> solid.routeLdp(WebServerAgent)
          ~> check {
            status shouldEqual OK
            testACLLinks(rootUri.withPath(Uri.Path./), response.headers[Link])
          }

        val test = new SolidTestClient(solid, WebServerAgent)

        info("create a new resource </Hello> with POST and read it 3 times")
        val newUri = test.newResource(rootC, Slug("Hello"), "Hello World!", Some(rootACL))

        newUri equals toUri("/Hello")
        info("read the new resource as admin. No link to default acl, since there is none")
        test.read(newUri, "Hello World!", 3, Some(rootACL))

        info("create 3 more resources with the same Slug and GET them too")
        for count <- (2 to 5).toList do
           val createdUri = test.newResource(rootC, Slug("Hello"), s"Hello World $count!",
             Some(rootACL))
           assert(createdUri.path.endsWith(s"Hello_$count"))
           test.read(createdUri, s"Hello World $count!", 3, Some(rootACL))

        info("Delete the first created resource </Hello>")
        Req.Delete(newUri) ~> solid.routeLdp(WebServerAgent) ~> check {
          status shouldEqual NoContent
        }

        info("Try GET deleted </Hello>")
        Req.Get(newUri).withHeaders(Accept(`*/*`)) ~> solid.routeLdp() ~> check {
          status shouldEqual Gone
        }

        info("Try GET its archive </Hello.archive/>")
        Req.Get(toUri("/Hello.archive/")).withHeaders(Accept(`*/*`)) ~> solid.routeLdp() ~> check {
          status shouldEqual NotFound
        }

        info("Create a new Container </blog/>")
        val blogDir: Uri = test.newContainer(rootC, Slug("blog"), Some(rootACL))
        blogDir shouldEqual toUri("/blog/")

        info("enable creation a new resource with POST in the new container and read it 3 times")
        val content      = "My First Blog Post is great"
        val firstBlogUri = test.newResource(blogDir, Slug("First Blog"), content, Some(rootACL))
        firstBlogUri equals toUri("/blog/FirstBlog")
        test.read(firstBlogUri, content, 3, Some(rootACL))

        for count <- (2 to 5).toList do
           val content = s"My Cat had $count babies"
           val blogDir = test.newContainer(rootC, Slug("blog"), Some(rootACL))
           blogDir shouldEqual toUri(s"/blog_$count/")
           val firstBlogUri = test.newResource(blogDir, Slug(s"A Blog in $count"), content,
             Some(rootACL))
           firstBlogUri equals toUri(s"/blog_$count/ABlogIn$count")
           test.read(firstBlogUri, content, 2, Some(rootACL))
           // todo: read contents of dir to see contents added
        end for

        info("give /blog/ its own ACL")
        val aclBlogTurtle = turtleWriter.asString(ServerData.pplAcl._2, None).get
        val blogDirACL    = blogDir ?/ ".acl"
        Req.Put(blogDirACL).withEntity(HttpEntity(`text/turtle`, aclBlogTurtle)) ~>
          solid.routeLdp(WebServerAgent) ~> check {
            status shouldEqual OK
            testACLLinks(blogDir, response.headers[Link])
          }

        info("check that any new file in created in the new blog dir has the acl of the dir")
        for count <- 6 to 7 do
           val firstBlogUri = test.newResource(blogDir, Slug(s"A Blog in $count"), content,
             Some(blogDirACL))
           firstBlogUri equals toUri(s"/blog/ABlogIn$count")
           test.read(firstBlogUri, content, 2, Some(blogDirACL))

        val paths = List(
          blogDir ?/ "2023" / "04" / "30" / "solidServer",
          blogDir ?/ "2023" / "05" / "01" / "Holiday",
          blogDir ?/ "2023" / "05" / "03" / "talk"
        )
        info("create blogs using PUT")
        for pu <- paths do
           info("going to PUT to " + pu)
           Req.Put(pu).withEntity(
             HttpEntity(`text/plain`.withCharset(HttpCharsets.`UTF-8`), "Hello")
           ) ~>
             solid.routeLdp(WebServerAgent) ~> check {
               status shouldEqual Created
               info("received result " + response)
               testACLLinks(pu, response.headers[Link], Some(blogDirACL))
             }

     }

     "Restarted. We now build up the ACLs and test again under different identities" in withServer {
       solid =>
          val wsAgentClient = new SolidTestClient(solid, WebServerAgent)
          val ownerClient   = new SolidTestClient(solid, WebIdAgent(owner))
          val anonClient    = new SolidTestClient(solid, new Anonymous)
          import LinkParams.*
          import TestSolidRouteSpec.*

          info("1. Edit default root acl.")
          info("1.1 Get Container to find acl Link relation")
          var rootAclUri: Uri = null

          Req.Get(rootUri / "").withHeaders(Accept(`*/*`)) ~> solid.routeLdp(
            WebServerAgent
          ) ~> check {
            status shouldEqual OK
            val links: Seq[Link] = response.headers[Link]
            links shouldNot be(Seq())
            val aclLinks: Seq[Uri] = aclLinkHeaders(links)
            assert(
              aclLinks.size == 1,
              "We should have one acl. " +
                "What do we do if we have more? How would we know which to edit?\n" +
                s"Header received: $headers"
            )
            val aclEffLinks: Seq[Uri] = aclEffectiveLinkHeaders(links)
            assert(
              aclEffLinks.size == 0,
              "We should have no effective acl links. " +
                "If there is an existing acl, then there need be no effective acl" +
                s"Header received: $headers"
            )
            rootAclUri = aclLinks.head
          }

          // todo: should it be empty or return a file does not exist?
          // in any case it could be argued that the server should not start if there is no file here
          info(s"1.2 fetch root ACL graph <$rootAclUri>? it should be empty")
          Req.Get(rootAclUri).withHeaders(Accept(`text/turtle`)) ~> solid.routeLdp(
            WebServerAgent
          ) ~> check {
            status shouldEqual NotFound
//            given un: FromEntityUnmarshaller[Rdf#Graph] = RdfParser.rdfUnmarshaller(rootAclUri)
//            val g: Rdf#Graph                            = responseAs[Rdf#Graph]
//             todo: but this is not a satisfactory response (minor)
//            assert(g.size == 0, s"The root ACL should be empty on startup, but it is $g")
          }

          info("1.3 PUT new graph to ACL")
          val aclTurtle = turtleWriter.asString(relativeRootACLGr, None).get
          Req.Put(rootAclUri).withEntity(HttpEntity(`text/turtle`, aclTurtle)) ~>
            solid.routeLdp(WebServerAgent) ~> check {
              status shouldEqual OK
              info(s"response is $response")
//              testACLLinks(rootUri, response.headers[Link], debug = true)
            }

          // todo: add a test with owl:imports, now that we have replaced that

          info("1.4 GET new changed ACL and check it is the changed version")
          Req.Get(rootAclUri).withHeaders(Accept(`text/turtle`)) ~> solid.routeLdp(
            WebServerAgent
          ) ~> check {
            status shouldEqual OK
            given un: FromEntityUnmarshaller[Rdf#Graph] = RdfParser.rdfUnmarshaller(rootAclUri)
            val g: Rdf#Graph                            = responseAs[Rdf#Graph]
            assert(
              g isIsomorphicWith rootACLGr,
              "We should get the same graph as we just PUT to the server"
            )
            info(s"info 1.4.1 test that $rootAclUri links to itself")
            val linkOpt: Seq[Link] = response.headers[Link]
            linkOpt shouldNot be(Seq())
            aclLinkHeaders(linkOpt) should contain(rootAclUri)
          }

          info("1.5 Attempt and fail to Create a new Hello resource as Anonymous")
          Req.Post(rootC, HttpEntity("Testing security")).withHeaders(Slug("Test")) ~>
            solid.routeLdp(Anonymous()) ~> check {
              status shouldEqual Unauthorized
              // the error should point us to the ACL of the container
              testACLLinks(rootC, response.headers[Link])
            }

          info(
            "1.6 create more resources with the same Slug(Hello) and GET them too - the deleted one is not overwritten"
          )
          for count <- (6 to 9).toList do
             val createdUri = ownerClient.newResource(rootC, Slug("Hello"),
               s"Hello World $count!",
               Some(rootACL))
             assert(
               createdUri.path.endsWith(s"Hello_$count"),
               s"path of <$createdUri> should end with 'Hello_$count'"
             )
             val acl = createdUri.sibling(createdUri.fileName.get + ".acl")
             info(s"Created <$createdUri>, try fetching its acl <$acl> next")
             Req.Get(acl).withHeaders(Accept(`text/turtle`)) ~> solid.routeLdp(
               WebServerAgent
             ) ~> check {
               status shouldEqual NotFound
             }
             info(s"Read <$createdUri> as anonymous")
             anonClient.read(createdUri, s"Hello World $count!", 2, Some(rootACL))
          end for
          info(
            "create more blogs and GET them too with same slug. Numbering continues where it left off."
          )
          for count <- (6 to 7).toList do
             val blogDir = wsAgentClient.newContainer(rootC, Slug("blog"), Some(rootACL))
             blogDir shouldEqual toUri(s"/blog_$count/")
     }
   }

   class SolidTestClient(solid: Solid, agent: Agent = new Anonymous):
      def newResource(
          baseDir: Uri,
          slug: Slug,
          text: String,
          effectiveAcl: Option[Uri] = None,
          debug: Boolean = false
      ): Uri =
        Req.Post(baseDir, HttpEntity(text)).withHeaders(slug) ~>
          solid.routeLdp(agent) ~> check {
            if debug then info(s"POST to <$baseDir> resulted in $response")
            status shouldEqual Created
            val newName = header[Location].get.uri
            testACLLinks(newName, response.headers[Link], effectiveAcl, debug)
            val loc: Location = header[Location].get
            loc.uri
          }

      def newContainer(
          baseDir: Uri,
          slug: Slug,
          effectiveAcl: Option[Uri] = None,
          debug: Boolean = false
      ): Uri =
        Req.Post(baseDir).withHeaders(slug, Link(BasicContainer.LDPLinkHeaders)) ~>
          solid.routeLdp(agent) ~> check {
            if debug then info(s"POST to <$baseDir> returned $response")
            status shouldEqual Created
            val newName = header[Location].get.uri
            testACLLinks(newName, response.headers[Link], effectiveAcl, debug)
            header[Location].get.uri
          }

      def read(
          url: Uri,
          text: String,
          times: Int = 1,
          effectiveAcl: Option[Uri] = None,
          debug: Boolean = false
      ): Unit =
        for _ <- 1 to times do
           Req.Get(url).withHeaders(Accept(`*/*`)) ~> solid.routeLdp(agent) ~> check {
             if debug then info(s"response to GET on <$url> is $response")
             status shouldEqual OK
             responseAs[String] shouldEqual text
             testACLLinks(url, response.headers[Link], effectiveAcl, debug)
           }
   end SolidTestClient

   def testACLLinks(
       fileUri: Uri,
       links: Seq[Link],
       effectiveAcl: Option[Uri] = None,
       debug: Boolean = false
   ): Assertion =
      val acl: Uri = fileUri.sibling(fileUri.path.reverse.head.toString + ".acl")
      //        fileUri.copy(path =
      //        fileUri.path.reverse.tail ?/ fileUri.path.reverse.head.toString + ".acl")
      if debug then info(s"Links for <$fileUri> are $links")
      links shouldNot be(None)
      aclLinkHeaders(links).size should be(1)
      aclLinkHeaders(links) should contain(acl)
      effectiveAcl match
       case Some(effacl) =>
         aclEffectiveLinkHeaders(links) should be(Seq(effacl))
       case None =>
         aclEffectiveLinkHeaders(links).size should be(0)
   end testACLLinks
