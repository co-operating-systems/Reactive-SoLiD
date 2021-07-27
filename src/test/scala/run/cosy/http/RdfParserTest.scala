package run.cosy.http

import akka.http.scaladsl.model.{HttpResponse, ResponseEntity}
import run.cosy.http.RDFMediaTypes.*
import run.cosy.http.RdfParser.*
import run.cosy.ldp.BasicACLTestServer.rootACL
import run.cosy.ldp.{BasicACLTestServer, WebServers}

import scala.util.Try

class RdfParserTest extends munit.FunSuite {

	test("test highestPriortyRDFMediaType") {
		assertEquals(highestPriortyRDFMediaType(List(`text/turtle`)),Some(`text/turtle`))
		assertEquals(highestPriortyRDFMediaType(
			Seq(`text/turtle`.withQValue(0.8),`application/n-quads`)),Some(`application/n-quads`))
	}

	test("toResponseEntity") {
		val resp: Try[ResponseEntity] = toResponseEntity(rootACL,`text/turtle`)
		assert(resp.isSuccess, "We should have had a response")
		val entity = resp.get.httpEntity
		assertEquals(entity.contentType, `text/turtle`.toContentType)
	}

	test("response") {
		import akka.http.scaladsl.model.StatusCodes.OK
		val res: HttpResponse = response(rootACL,Seq(`application/n-quads`.withQValue(0.8),`text/turtle`))
		assertEquals(res.status,OK)
	}

}