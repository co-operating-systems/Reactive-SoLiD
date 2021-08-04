package run.cosy.ldp

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.http.javadsl.model.headers.HttpCredentials
import akka.http.scaladsl.model.headers.*
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.HttpHeader.ParsingResult.Ok
import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.StatusCodes.*
import akka.http.scaladsl.server.AuthenticationFailedRejection
import akka.http.scaladsl.server.AuthenticationFailedRejection.CredentialsRejected
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import run.cosy.http.auth.{Agent, Anonymous, HttpSigDirective, WebIdAgent, WebServerAgent}
import run.cosy.http.headers.Slug
import run.cosy.ldp.fs.BasicContainer
import run.cosy.http.util.UriX.*
import run.cosy.ldp.testUtils.TmpDir.{createDir, deleteDir}
import run.cosy.{Solid, SolidTest}
import run.cosy.RDF.*
import run.cosy.RDF.ops.*
import run.cosy.RDF.Prefix.wac
import run.cosy.http.RDFMediaTypes.`text/turtle`
import run.cosy.http.{RDFMediaTypes, RdfParser}

import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt

object TestSolidRouteSpec {
	import akka.http.scaladsl.model.headers.LinkParams.*
	val AccessControl = wac.accessControl.getString
	
	val rootUri: Uri = Uri("http://localhost:8080")
	val ServerData = BasicACLTestServer(rootUri)

	val relativeRootACLGr: Rdf#Graph = ServerData.rootACL._2
	val rootACLGr = ServerData.absDB(ServerData.rootACL._1)
	val owner = rootUri.withPath(Uri.Path("/owner")).withFragment("i")

	def aclLinkHeaders(link: Link): Seq[Uri] = link.values.collect {
		case lv@LinkValue(uri, params) if params.exists {
			case rel("acl") => true
			case rel(AccessControl) => true
			case _ => false
		} => uri
	}
}

class TestSolidRouteSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

	import akka.http.scaladsl.client.RequestBuilding as Req
	import akka.http.scaladsl.server.Directives
	import TestSolidRouteSpec.*
	
	val dirPath: Path = createDir("solidTest_")

	// This test does not use the classic APIs,
	// so it needs to adapt the system:

	import akka.actor.typed.scaladsl.adapter.*

	given typedSystem: ActorSystem[Nothing] = system.toTyped

	implicit val timeout  : Timeout   = Timeout(5000.milliseconds)
	implicit val scheduler: Scheduler = typedSystem.scheduler

	given registry: ResourceRegistry = ResourceRegistry(typedSystem)

	import akka.http.scaladsl.model.headers

	def toUri(path: String): Uri = rootUri.withPath(Uri.Path(path))

	def withServer(test: Solid => Any): Unit =
		val testKit                                      = ActorTestKit()
		val rootCntr: Behavior[BasicContainer.AcceptMsg] = BasicContainer(rootUri, dirPath)
		val rootActr: ActorRef[BasicContainer.AcceptMsg] = testKit.spawn(rootCntr, "solid")
		val solid                                        = new Solid(rootUri, dirPath, registry, rootActr)
		try {
			test(solid)
		} finally testKit.shutdownTestKit()
	end withServer

	override def afterAll(): Unit = deleteDir(dirPath)

	"The Server" when {
		val rootC = toUri("/")

		"started for the first time. All actions done as WebServerAgent" in withServer { solid =>
			info(s"we GET <$rootUri>")
			Req.Get(rootUri).withHeaders(Accept(`*/*`)) ~> solid.routeLdp(WebServerAgent) ~> check {
				status shouldEqual MovedPermanently
				header[Location].get shouldEqual Location(rootC)
			}

			val test = new SolidTestClient(solid, WebServerAgent)

			info("create a new resource </Hello> with POST and read it 3 times")
			val newUri = test.newResource(rootC, Slug("Hello"), "Hello World!")
			newUri equals toUri("/Hello")
			test.read(newUri, "Hello World!", 3)

			info("create 3 more resources with the same Slug and GET them too")
			for (count <- (2 to 5).toList) {
				val createdUri = test.newResource(rootC, Slug("Hello"), s"Hello World $count!")
				assert(createdUri.path.endsWith(s"Hello_$count"))
				test.read(createdUri, s"Hello World $count!", 3)
			}

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
			val blogDir = test.newContainer(rootC, Slug("blog"))
			blogDir shouldEqual toUri("/blog/")

			info("enable creation a new resource with POST in the new container and read it 3 times")
			val content      = "My First Blog Post is great"
			val firstBlogUri = test.newResource(blogDir, Slug("First Blog"), content)
			firstBlogUri equals toUri("/blog/FirstBlog")
			test.read(firstBlogUri, content, 3)

			for (count <- (2 to 5).toList) {
				val content = s"My Cat had $count babies"
				val blogDir = test.newContainer(rootC, Slug("blog"))
				blogDir shouldEqual toUri(s"/blog_$count/")
				val firstBlogUri = test.newResource(blogDir, Slug(s"A Blog in $count"), content)
				firstBlogUri equals toUri(s"/blog_$count/ABlogIn$count")
				test.read(firstBlogUri, content, 2)
				//todo: read contents of dir to see contents added
			}
		}

		"Restarted. We now build up the ACLs and test again under different identities" in withServer { solid =>
			val wsAgentClient = new SolidTestClient(solid, WebServerAgent)
			val ownerClient = new SolidTestClient(solid,WebIdAgent(owner))
			val anonClient = new SolidTestClient(solid,new Anonymous)
			import LinkParams.*
			import TestSolidRouteSpec.*

			info("1. Edit default root acl.")
			info("1.1 Get Container to find acl Link relation")
			var rootAclUri: Uri = null

			Req.Get(rootUri/"").withHeaders(Accept(`*/*`)) ~> solid.routeLdp(WebServerAgent) ~> check {
				status shouldEqual OK
				val linkOpt = header[Link]
				linkOpt shouldNot be(None)
				val aclLinks: Seq[Uri] = aclLinkHeaders(linkOpt.get)
				assert(aclLinks.size == 1, "We should have one acl. " +
					"What do we do if we have more? How would we know which to edit?\n" +
					s"Header received: $headers")
				rootAclUri = aclLinks.head
			}

			info(s"1.2 fetch root ACL graph <$rootAclUri>? it should be empty")
			Req.Get(rootAclUri).withHeaders(Accept(`text/turtle`)) ~> solid.routeLdp(WebServerAgent) ~> check {
				status shouldEqual OK
				given un: FromEntityUnmarshaller[Rdf#Graph] = RdfParser.rdfUnmarshaller(rootAclUri)
				val g: Rdf#Graph = responseAs[Rdf#Graph]
				//todo: but this is not a satisfactory response (minor)
				assert(g.size == 0, s"The root ACL should be empty on startup, but it is $g")
			}

			info("1.3 PUT new graph to ACL")
			val aclTurtle = turtleWriter.asString(relativeRootACLGr, None).get
			Req.Put(rootAclUri).withEntity(HttpEntity(`text/turtle`, aclTurtle)) ~>
				solid.routeLdp(WebServerAgent) ~> check {
				status shouldEqual OK
			}

			info("1.4 GET new changed ACL and check it is the changed version")
			Req.Get(rootAclUri).withHeaders(Accept(`text/turtle`)) ~> solid.routeLdp(WebServerAgent) ~> check {
				status shouldEqual OK
				given un: FromEntityUnmarshaller[Rdf#Graph] = RdfParser.rdfUnmarshaller(rootAclUri)
				val g: Rdf#Graph = responseAs[Rdf#Graph]
				assert(g isIsomorphicWith rootACLGr, "We should get the same graph as we just PUT to the server")
				info(s"info 1.4.1 test that $rootAclUri links to itself")
				val linkOpt: Option[Link] = header[Link]
				linkOpt shouldNot be(None)
				aclLinkHeaders(linkOpt.get) should contain(rootAclUri)
			}
			
			info("1.5 Attempt and fail to Create a new Hello resource as Anonymous")
			Req.Post(rootC, HttpEntity("Testing security")).withHeaders(Slug("Test")) ~>
				solid.routeLdp(Anonymous()) ~> check {
				status shouldEqual Unauthorized
				val linkOpt = header[Link]
				linkOpt shouldNot be(None)
				val aclLinks: Seq[Uri] = aclLinkHeaders(linkOpt.get)
				aclLinks.size shouldEqual 1
			}

			info("1.6 create more resources with the same Slug(Hello) and GET them too - the deleted one is not overwritten")
			for (count <- (6 to 9).toList) {
				val createdUri = ownerClient.newResource(rootC, Slug("Hello"), s"Hello World $count!")
				assert(createdUri.path.endsWith(s"Hello_$count"),s"path of <$createdUri> should end with 'Hello_$count'")
				val acl = createdUri.sibling(createdUri.fileName.get + ".acl")
				info(s"Created <$createdUri>, try fetching its acl <$acl> next")
				Req.Get(acl).withHeaders(Accept(`text/turtle`)) ~> solid.routeLdp(WebServerAgent) ~> check {
					status shouldEqual OK
					responseAs[String] shouldNot be("")
					val linkOpt = header[Link]
					linkOpt shouldNot be(None)
					val aclLinks: Seq[Uri] = aclLinkHeaders(linkOpt.get)
					aclLinks should contain(acl)
				}
				info(s"Read <$createdUri> as anonymous")
				anonClient.read(createdUri, s"Hello World $count!", 2)
			}

			info("create more blogs and GET them too with same slug. Numbering continues where it left off.")
			for (count <- (6 to 7).toList) {
				val blogDir = wsAgentClient.newContainer(rootC, Slug("blog"))
				blogDir shouldEqual toUri(s"/blog_$count/")
				//todo: read contents of dir to see contents added
			}
		}
	}

	class SolidTestClient(solid: Solid, agent: Agent = new Anonymous):
		def newResource(baseDir: Uri, slug: Slug, text: String): Uri =
			Req.Post(baseDir, HttpEntity(text)).withHeaders(slug) ~>
				solid.routeLdp(agent) ~> check {
					status shouldEqual Created
					val loc: Location = header[Location].get
					loc.uri
				}

		def newContainer(baseDir: Uri, slug: Slug): Uri =
			Req.Post(baseDir).withHeaders(slug, BasicContainer.LinkHeaders) ~>
				solid.routeLdp(agent) ~> check {
				status shouldEqual Created
				header[Location].get.uri
			}

		def read(url: Uri, text: String, times: Int = 1) =
			for (_ <- 1 to times)
				Req.Get(url).withHeaders(Accept(`*/*`)) ~> solid.routeLdp(agent) ~> check {
					status shouldEqual OK
					responseAs[String] shouldEqual text
				}
	end SolidTestClient

}
