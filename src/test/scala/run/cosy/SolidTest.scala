package run.cosy

import munit._
import munit.Location._
import _root_.akka.http.scaladsl.model.Uri

class SolidTest extends munit.FunSuite {
	val root : Uri = Uri("https://ted.example")
	val u1 : Uri = Uri("https://ted.example/foo/bar")

	test("explore URIs") {
		val endsWithBar = u1.path / ""
		assertEquals(endsWithBar.toString(),"/foo/bar/",u1)
	}

	test("pathToList") {
		import Solid.pathToList


		val proot = pathToList(root.path)
		assertEquals(proot,List(),root.path)
		val root2 = root.path /""
		val proot2 = pathToList(root2)
		assertEquals(proot2,List(),root2)

		val pl = pathToList(u1.path)
		assertEquals(pl,List("foo","bar"),pl)
		val p2 = u1.path/""
		assertEquals(pathToList(p2), List("foo","bar"),p2)
	}
}
