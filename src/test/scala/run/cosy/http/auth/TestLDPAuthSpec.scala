/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http.auth

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.http.javadsl.model.headers.HttpCredentials
import akka.http.scaladsl.model.headers.{
  Accept,
  Authorization,
  GenericHttpCredentials,
  HttpChallenge,
  Link,
  Location,
  `WWW-Authenticate`
}
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.AuthenticationFailedRejection
import akka.http.scaladsl.server.AuthenticationFailedRejection.CredentialsRejected
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.Timeout
import com.nimbusds.jose.jwk.JWK
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import run.cosy.{Solid, SolidTest}
import run.cosy.http.headers.Slug
import run.cosy.ldp.Messages
import run.cosy.ldp.fs.BasicContainer
import org.w3.banana.*

import java.nio.file.{Files, Path}
import concurrent.duration.DurationInt
import run.cosy.http.auth.WebServerAgent
import run.cosy.ldp.ResourceRegistry
import run.cosy.ldp.testUtils.TmpDir.{createDir, deleteDir}
import run.cosy.http.RDFMediaTypes.{`text/turtle`, *}
import run.cosy.ldp.SolidPostOffice
import run.cosy.http.RdfParser.{rdfRequest, rdfUnmarshaller}
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import org.scalatest.Assertion
import run.cosy.http.util.UriX.*
import run.cosy.ldp.ACInfo.*
import run.cosy.ldp.TestSolidRouteSpec.rootUri

import javax.activation.MimeType

class TestSolidLDPAuthSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

   import akka.http.scaladsl.server.Directives
   import akka.http.scaladsl.client.{RequestBuilding as Req}
   import _root_.run.cosy.RDF.{given, *}
   import _root_.run.cosy.RDF.ops.{given, *}
   val security = SecurityPrefix[Rdf]
   val foaf     = FOAFPrefix[Rdf]
   val cert     = CertPrefix[Rdf]

   val dirPath: Path = createDir("solidTest_")

   // This test does not use the classic APIs,
   // so it needs to adapt the system:
   import akka.actor.typed.scaladsl.adapter.{given, *}
   given typedSystem: ActorSystem[Nothing] = system.toTyped
   given registry: SolidPostOffice         = SolidPostOffice(typedSystem)
   implicit val timeout: Timeout           = Timeout(5000.milliseconds)
   implicit val scheduler: Scheduler       = typedSystem.scheduler

   val rootUri: Uri = Uri("http://localhost:8080")
   val rootAcl: Uri = Uri("http://localhost:8080/.acl")

   import akka.http.scaladsl.model.headers

   def toUri(path: String): Uri = rootUri.withPath(Uri.Path(path))

   def withServer(test: Solid => Any): Unit =
      val testKit = ActorTestKit()
      val rootCntr: Behavior[BasicContainer.AcceptMsg] = BasicContainer(rootUri, dirPath,
        Root(rootAcl))
      val rootActr: ActorRef[BasicContainer.AcceptMsg] = testKit.spawn(rootCntr, "solid")
      given sys: ActorSystem[?]                        = testKit.internalSystem
      SolidPostOffice(sys).addRoot(rootUri, rootActr)
      val solid = new Solid(rootUri, dirPath)
      try
        test(solid)
      finally testKit.shutdownTestKit()
   end withServer

   /** todo: TestSolidRouteSpec has something similar, they should be extracrted */
   class SolidTestPost(solid: Solid, agent: Agent = new Anonymous):

      def newResource(
          baseDir: Uri,
          slug: Slug,
          text: String,
          ct: ContentType.NonBinary = ContentTypes.`text/plain(UTF-8)`
      ): Uri =
        HttpRequest(HttpMethods.POST, baseDir, Seq[HttpHeader](slug), HttpEntity(ct, text)) ~>
          solid.routeLdp(agent) ~> check {
            status shouldEqual StatusCodes.Created
            val loc: Location = header[Location].get
            loc.uri
          }

      def newContainer(baseDir: Uri, slug: Slug): Uri =
        Req.Post(baseDir).withHeaders(slug, Link(BasicContainer.LDPLinkHeaders)) ~>
          solid.routeLdp(agent) ~> check {
            status shouldEqual StatusCodes.Created
            header[Location].get.uri
          }

      def readRDF(url: Uri, expected: Rdf#Graph) =
        rdfRequest(url) ~> solid.routeLdp(agent) ~> check {
          given FromEntityUnmarshaller[Rdf#Graph] = rdfUnmarshaller(url)
          val result                              = responseAs[Rdf#Graph]
          assert(result.isIsomorphicWith(expected), s"result=$result ,\n expected=$expected")
          result
        }

      def read(
          url: Uri,
          text: String,
          times: Int = 1,
          effectiveAcl: Option[Uri] = None,
          debug: Boolean = false
      ): Unit =
        for _ <- 1 to times do
           Req.Get(url).withHeaders(Accept(MediaRanges.`*/*`)) ~> solid.routeLdp(agent) ~> check {
             if debug then info(s"response to GET on <$url> is $response")
             status shouldEqual OK
             responseAs[String] shouldEqual text
             testACLLinks(url, response.headers[Link], effectiveAcl, debug)
           }

      def testACLLinks(
          fileUri: Uri,
          links: Seq[Link],
          effectiveAcl: Option[Uri] = None,
          debug: Boolean = false
      ): Assertion =
         import _root_.run.cosy.http.util.UriX.sibling
         val acl: Uri = fileUri.sibling(fileUri.path.reverse.head.toString + ".acl")
         //        fileUri.copy(path =
         //        fileUri.path.reverse.tail ?/ fileUri.path.reverse.head.toString + ".acl")
         if debug then info(s"Links for <$fileUri> are $links")
         links shouldNot be(None)
         import _root_.run.cosy.ldp.TestSolidRouteSpec.{aclLinkHeaders, aclEffectiveLinkHeaders}
         aclLinkHeaders(links).size should be(1)
         aclLinkHeaders(links) should contain(acl)
         effectiveAcl match
          case Some(effacl) =>
            aclEffectiveLinkHeaders(links) should be(Seq(effacl))
          case None =>
            aclEffectiveLinkHeaders(links).size should be(0)
      end testACLLinks

   end SolidTestPost

   import bobcats.HttpMessageSignaturesV07.`test-key-rsa-pss`
   val jwk = JWK.parseFromPEMEncodedObjects(`test-key-rsa-pss`.publicKey)

   val lit: Rdf#Node = Literal(jwk.toJSONString, rdf.JSON)
   val keyGraph: Rdf#Graph = {
     URI("#") -- security.publicKeyJwk ->- PointedGraph(lit, Graph.empty)
   }.graph

   "The Server" when {
     val rootC = toUri("/")

     "started for the first time" in withServer { solid =>
        info(s"we GET <$rootUri>")
        Req.Get(rootUri).withHeaders(Accept(MediaRanges.`*/*`)) ~> solid.routeLdp(
          WebServerAgent
        ) ~> check {
          status shouldEqual StatusCodes.MovedPermanently
          header[Location].get shouldEqual Location(rootC)
        }

        val test = new SolidTestPost(solid, WebServerAgent)

        info("create a new </key> with POST")
        val gtoSendTry: scala.util.Try[String] = turtleWriter.asString(keyGraph, None)
        assert(gtoSendTry.isSuccess)

        val newUri =
          test.newResource(rootC, Slug("key"), gtoSendTry.get, `text/turtle`.toContentType)
        newUri equals toUri("/key")

        info("verify it can be read and that it is isomorphic to what was sent")

        val canonicalKeyGraph = keyGraph.resolveAgainst(URI(newUri.toString()))
        test.readRDF(newUri, canonicalKeyGraph)

        info("GET the same document by authenticating with the key")
        import org.w3.banana.binder.PGBinder.given
        info("create a card with a key pointing to the key")
        val relGraph =
          URI("#i") -- foaf.name ->- "Henry Story"
            -- cert.key ->- PointedGraph[Rdf](
              URI("/key#")
            ) // todo: for some reason we need PointedGraph here...
            -- foaf.workInfoHomepage ->- PointedGraph[Rdf](URI("https://co-operating.systems/"))

        val createdUri = test.newResource(
          rootC,
          Slug("card"),
          """@prefix foaf: <http://xmlns.com/foaf/0.1/> .
            |@prefix cert: <http://www.w3.org/ns/auth/cert#> .
            |<#i> foaf:name "Henry Story";
            |	cert:key </key#>;
            |	foaf:workInfoHomepage <https://co-operating.systems/> .
            |""".stripMargin,
          `text/turtle`.toContentType
        )
        assert(createdUri.path.endsWith("card"))
        test.readRDF(createdUri, relGraph.graph.resolveAgainst(URI(createdUri.toString())))

        info("Delete the first created resource </Hello>")
        Req.Delete(newUri) ~> solid.routeLdp(WebServerAgent) ~> check {
          status shouldEqual StatusCodes.NoContent
        }

        info("Try GET deleted </Hello>")
        Req.Get(newUri).withHeaders(Accept(MediaRanges.`*/*`)) ~> solid.routeLdp(
          WebServerAgent
        ) ~> check {
          status shouldEqual StatusCodes.Gone
        }

        info("Try GET its archive </Hello.archive/>")
        Req.Get(toUri("/Hello.archive/")).withHeaders(Accept(MediaRanges.`*/*`))
          ~> solid.routeLdp(WebServerAgent) ~> check {
            status shouldEqual StatusCodes.NotFound
          }

        info("Create a new Container </blog/>")
        val blogDir = test.newContainer(rootC, Slug("blog"))
        blogDir shouldEqual toUri("/blog/")

        info("enable creation a new resource with POST")
        val content      = "My First Blog Post is great"
        val firstBlogUri = test.newResource(blogDir, Slug("First Blog"), content)
        firstBlogUri equals toUri("/blog/FirstBlog")

        info(s"read the new resource $firstBlogUri")
        test.read(firstBlogUri, content, 3, Some(rootUri.withSlash))

        info("create three new containers resource with POST")
        for count <- (2 to 5).toList do
           val content = s"My Cat had $count babies"
           val blogDir = test.newContainer(rootC, Slug("blog"))
           blogDir shouldEqual toUri(s"/blog_$count/")
           val firstBlogUri = test.newResource(blogDir, Slug(s"A Blog in $count"), content)
           firstBlogUri equals toUri(s"/blog_$count/ABlogIn$count")
           test.read(firstBlogUri, content, 2, Some(rootUri.withSlash ))
           // todo: read contents of dir to see contents added
     }

//		"restarted" in withServer { solid =>
//			val test = new SolidTestPost(solid, WebServerAgent)
//
//			info("create more resources with the same Slug(Hello) and GET them too - the deleted one is not overwritten")
//			for (count <- (6 to 9).toList) {
//				val createdUri = test.newResource(rootC, Slug("Hello"), s"Hello World $count!")
//				assert(createdUri.path.endsWith(s"Hello_$count"))
//				test.read(createdUri,s"Hello World $count!", 3)
//			}
//
//			info("create more blogs and GET them too with same slug. Numbering continues where it left off.")
//			for (count <- (6 to 7).toList) {
//				val blogDir = test.newContainer(rootC, Slug("blog"))
//				blogDir shouldEqual toUri(s"/blog_$count/")
//				//todo: read contents of dir to see contents added
//			}
   }

   override def afterAll(): Unit = deleteDir(dirPath)
