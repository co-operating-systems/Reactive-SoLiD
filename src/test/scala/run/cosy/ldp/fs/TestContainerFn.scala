package run.cosy.ldp.fs

class TestContainerFn extends munit.FunSuite {
	import akka.http.javadsl.model.MediaType
	import akka.http.scaladsl.model
	import model.ContentTypes._
	import model.HttpMethods.POST
	import model.headers.`Content-Type`
	import model.{ContentType, ContentTypes, HttpRequest, Uri, HttpEntity}
	import run.cosy.http.Headers.Slug
	import run.cosy.http.{Encoding => cEncoding}
	import run.cosy.ldp.fs.{BasicContainer=>bc}

	import java.time.{Clock, Instant, ZoneId}
	
	val testClock = Clock.fixed(Instant.EPOCH,ZoneId.of("UTC"))
	
	def testSlug(slugTxt: String, ct: ContentType, expectedLinkName: String, expectedLinkTo: String): Unit =
		import akka.util.ByteString
		import cEncoding.*
		val req = HttpRequest(POST, Uri("/"),
			         Option(slugTxt).map(t=>Slug(t.asClean)).toSeq,
						HttpEntity(Option(ct).getOrElse(`application/octet-stream`),ByteString())
					)                                                  
		val (name, linkTo) = bc.createLinkNames(req)(using testClock)
		assertEquals(name,expectedLinkName,req)
		assertEquals(linkTo,expectedLinkTo,req)
	end testSlug
	
	test("Test Slug") {
		import akka.http.scaladsl.model.{MediaTypes=>mt}
		import akka.http.scaladsl.model.HttpCharsets.*
	
		//todo: clearly the default encoding from Akka, will not always be to everyone's liking
		testSlug("Readme.txt",ContentTypes.`text/plain(UTF-8)`,"Readme","Readme.conf") 
		testSlug("MyCatFelix",mt.`image/jpeg`.toContentType,"MyCatFelix","MyCatFelix.jpe")
		testSlug("MyCatFelix.jpg",mt.`image/gif`.toContentType,"MyCatFelix","MyCatFelix.gif")
		testSlug("Felix had a baby.md",mt.`text/markdown`.withCharset(`UTF-8`),"Felixhadababy","Felixhadababy.markdown")
		testSlug(null, mt.`video/mp4`.toContentType,"19700101-0","19700101-0.mp4")
		testSlug(null, null,"19700101-0","19700101-0.a")
		testSlug(null, mt.`application/octet-stream`.toContentType,"19700101-0","19700101-0.a")

		testSlug("Readme_2",ContentTypes.`text/plain(UTF-8)`,"Readme2","Readme2.conf")
	}
	
	test("Link relations") {
		import akka.http.scaladsl.model.HttpHeader
		import akka.http.scaladsl.model.headers.Link
		import akka.http.scaladsl.model.HttpHeader.ParsingResult.Ok
		import akka.http.scaladsl.model.headers.LinkParam
		import akka.http.scaladsl.model.headers.LinkParams.rel
		import akka.http.scaladsl.model.headers.LinkValue


		val Ok(l@Link(_), _) = HttpHeader.parse(
			"Link",
			"""<http://www.w3.org/ns/ldp#BasicContainer>; rel="type",
			  |<http://www.w3.org/ns/ldp#Resource>; rel="type"""".stripMargin.replace("\n", "")
		)
		val x = BasicContainer.filterLDPTypeLinks(Seq(l))
		assertEquals(x.size,2)
		assertEquals(x, Seq(Uri("http://www.w3.org/ns/ldp#BasicContainer"),Uri("http://www.w3.org/ns/ldp#Resource")))
	}
}
