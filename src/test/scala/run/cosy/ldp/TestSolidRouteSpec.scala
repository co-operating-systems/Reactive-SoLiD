package run.cosy.ldp

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.http.javadsl.model.headers.HttpCredentials
import akka.http.scaladsl.model.headers.*
import akka.http.scaladsl.model.*
import akka.http.scaladsl.server.AuthenticationFailedRejection
import akka.http.scaladsl.server.AuthenticationFailedRejection.CredentialsRejected
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import run.cosy.http.auth.{Agent, Anonymous, HttpSigDirective, WebServerAgent}
import run.cosy.http.headers.Slug
import run.cosy.ldp.fs.BasicContainer
import run.cosy.ldp.testUtils.TmpDir.{createDir, deleteDir}
import run.cosy.{Solid, SolidTest}

import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt

class TestSolidRouteSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

	import akka.http.scaladsl.client.RequestBuilding as Req
	import akka.http.scaladsl.server.Directives

	val dirPath: Path = createDir("solidTest_")

	// This test does not use the classic APIs,
	// so it needs to adapt the system:

	import akka.actor.typed.scaladsl.adapter.*
	val rootUri: Uri = Uri("http://localhost:8080")

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

		"started for the first time" in withServer { solid =>
			info(s"we GET <$rootUri>")
			Req.Get(rootUri).withHeaders(Accept(MediaRanges.`*/*`)) ~> solid.routeLdp(WebServerAgent) ~> check {
				status shouldEqual StatusCodes.MovedPermanently
				header[Location].get shouldEqual Location(rootC)
			}

			val test = new SolidTestPost(solid, WebServerAgent)

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
				status shouldEqual StatusCodes.NoContent
			}

			info("Try GET deleted </Hello>")
			Req.Get(newUri).withHeaders(Accept(MediaRanges.`*/*`)) ~> solid.routeLdp() ~> check {
				status shouldEqual StatusCodes.Gone
			}

			info("Try GET its archive </Hello.archive/>")
			Req.Get(toUri("/Hello.archive/")).withHeaders(Accept(MediaRanges.`*/*`)) ~> solid.routeLdp() ~> check {
				status shouldEqual StatusCodes.NotFound
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

		"restarted" in withServer { solid =>
			val test = new SolidTestPost(solid, WebServerAgent)

			info("create more resources with the same Slug(Hello) and GET them too - the deleted one is not overwritten")
			for (count <- (6 to 9).toList) {
				val createdUri = test.newResource(rootC, Slug("Hello"), s"Hello World $count!")
				assert(createdUri.path.endsWith(s"Hello_$count"))
				test.read(createdUri, s"Hello World $count!", 3)
			}

			info("create more blogs and GET them too with same slug. Numbering continues where it left off.")
			for (count <- (6 to 7).toList) {
				val blogDir = test.newContainer(rootC, Slug("blog"))
				blogDir shouldEqual toUri(s"/blog_$count/")
				//todo: read contents of dir to see contents added
			}
		}
	}

	class SolidTestPost(solid: Solid, agent: Agent = new Anonymous):
		def newResource(baseDir: Uri, slug: Slug, text: String): Uri =
			Req.Post(baseDir, HttpEntity(text)).withHeaders(slug) ~>
				solid.routeLdp(agent) ~> check {
				status shouldEqual StatusCodes.Created
				val loc: Location = header[Location].get
				loc.uri
			}

		def newContainer(baseDir: Uri, slug: Slug): Uri =
			Req.Post(baseDir).withHeaders(slug, BasicContainer.LinkHeaders) ~>
				solid.routeLdp(agent) ~> check {
				status shouldEqual StatusCodes.Created
				header[Location].get.uri
			}

		def read(url: Uri, text: String, times: Int = 1) =
			for (_ <- 1 to times)
				Req.Get(url).withHeaders(Accept(MediaRanges.`*/*`)) ~> solid.routeLdp(agent) ~> check {
					responseAs[String] shouldEqual text
				}
	end SolidTestPost

}
