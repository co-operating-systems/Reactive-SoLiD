package run.cosy.http.util

import run.cosy.http.util.UriX.*
import akka.http.scaladsl.model.Uri

class UriUtilTest extends munit.FunSuite {


	val card = Uri("https://bblfish.net/people/henry/card.ttl#me")
	val bbl = Uri("https://bblfish.net")
	val bblRt = Uri("https://bblfish.net/")
	val bblIndx = Uri("https://bblfish.net/index")

	test("uri.filename") {
		assertEquals(card.fileName,Some("card.ttl"))
		assertEquals(bbl.fileName,None)
		assertEquals(bblRt.fileName,None)
	}

	test("uri.withoutSlash") {
		assertEquals(card.withoutSlash,card)
		assertEquals(bbl.withoutSlash,bbl)
		assertEquals(bblRt.withoutSlash,bbl)
	}

	test("uri.sibling(bro)") {
		assertEquals(card.sibling("card"),Uri("https://bblfish.net/people/henry/card#me"))
		assertEquals(bbl.sibling("card"),Uri("https://bblfish.net/card"))
		assertEquals(bblRt.sibling("card"),Uri("https://bblfish.net/card"))
	}

	test("uri.ancestorOf(...)") {
		assert(bbl.ancestorOf(bbl))
		assert(bbl.ancestorOf(card))
		assert(bblRt.ancestorOf(card))
		assert(bblRt.ancestorOf(bblRt))
		assert(bblRt.ancestorOf(bblIndx))
	}

	test("path.container") {
		assertEquals(card.path.container, Uri.Path("/people/henry/"))
		assertEquals(Uri.Path./("").container, Uri.Path./)
	}

}
