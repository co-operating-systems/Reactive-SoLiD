/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp

import akka.http.scaladsl.model.Uri
import munit.*
import munit.Location.*
import run.cosy.Solid.pathToList
import run.cosy.ldp.SolidPostOffice

import scala.collection.immutable.HashMap

class PathDBInt extends PathDB[Int, String]:
   override def hasAcl(a: String): Boolean = !a.isEmpty

class TestRegistry extends munit.FunSuite:
   type DT = DirTree[Int, Option[String]]
   val dbInt         = new PathDBInt()
   val srcTstScHello = List("src", "test", "scala", "hello.txt")
   val srcTst        = srcTstScHello.take(2)
   val src           = srcTst.take(1)
   val srcTsSc       = srcTstScHello.take(3)

   // low level tests
   test("On ATree[Int, Option[String]]") {
     val rt1: DT = DirTree(1, None)

     val rt1_ = rt1.insert(1, Nil, None)
     assertEquals(rt1, rt1_)

     val rt1bis = rt1.insert(11, Nil, Some("hi"))
     assertEquals(rt1bis, DirTree(11, Some("hi")))

     // the insertion is ignored
     val rt2: DT = rt1.insert(2, srcTst, None)
     assertEquals(rt2, rt1)

     assertEquals(rt2.findClosest(Nil), (Nil, 1, None))
     assertEquals(rt2.findClosest(src), (src, 1, None))

     assertEquals(rt1.toClosestAPath(List("src")), (Left(Nil), List(("src", rt1))))

     val rt3: DT = rt1.insert(2, src, Some("coucou"))
     assertEquals(rt3, DirTree(1, None, HashMap("src" -> DirTree(2, Some("coucou")))))
     assertEquals(rt3.findClosest(src), (Nil, 2, Some("coucou")))

     val rt4 = rt3.insert(3, srcTst, None)
     assertEquals(rt4.findClosest(srcTst), (Nil, 3, None), rt4)
     assertEquals(rt4.findClosest(src), (Nil, 2, Some("coucou")), rt4)
     assertEquals(rt4.findClosest(srcTstScHello), (srcTstScHello.drop(2), 3, None), rt4)
     assertEquals(rt4,
       DirTree(1, None,
         HashMap("src" ->
           DirTree(2, Some("coucou"),
             HashMap("test" ->
               DirTree(3, None))))))

     val rt4new = rt4.replace(Some("55"), List("src", "test"))
     assertEquals(rt4new,
       DirTree(1, None,
         HashMap("src" ->
           DirTree(2, Some("coucou"),
             HashMap("test" ->
               DirTree(3, Some("55")))))))
     // attribute replacement cannot introduce new node, (as we don't know what the obj is)
     assertEquals(rt4.replace(Some("55"), List("src", "test", "blah")), rt4)

     // neither can we create intermediaries
     assertEquals(rt4.replace(Some("55"), List("src", "test", "blah", "blah")),
       rt4)

     val rt5 = rt4.insert(4, srcTsSc, Some("rt5"))
     assertEquals(rt5.findClosest(src), (Nil, 2, Some("coucou")), rt5)
     assertEquals(rt5.findClosest(srcTstScHello), (srcTstScHello.drop(3), 4, Some("rt5")), rt5)

     val rt6 = rt5.insert(5, srcTstScHello, Some("Hello"))
     assertEquals(rt6.findClosest(srcTstScHello), (Nil, 5, Some("Hello")), rt6)

     val rt7 = rt6.delete(srcTsSc)
     assertEquals(rt7, Some(rt4), rt7)

     assertEquals(rt7.get.delete(Nil), None)
   }

   test("URI to List") {
     val p1 = Uri.Path("/")
     assertEquals(pathToList(p1), Nil)

     val p2 = Uri.Path("/src/")
     assertEquals(pathToList(p2), List("src"))

     val p3 = Uri.Path("/src/test")
     assertEquals(pathToList(p3), List("src", "test"))

     val p4 = Uri.Path("/src/test/")
     assertEquals(pathToList(p4), List("src", "test"))
   }

   test("on path /") {
     import scala.language.implicitConversions
     import run.cosy.Solid
     implicit val UriPathToPath: Conversion[Uri.Path, List[String]] = Solid.pathToList

     val p1 = Uri.Path("/")
     dbInt.addActorRef(p1, 1, "ok")
     val r: Option[(List[String], Int, Option[Int])] = dbInt.getActorRef(p1)
     assertEquals(r, Some((List[String](), 1, Some(1))))

     val p2 = Uri.Path("/src")
     dbInt.addActorRef(p2, 2, "")
     val r2 = dbInt.getActorRef(p2)
     assertEquals(r2, Some((Nil, 2, Some(1))), dbInt.pathMap.get())

     val p3 = Uri.Path("/src/test/")
     dbInt.addActorRef(p3, 3, "..")
     assertEquals(dbInt.getActorRef(p3), Some((Nil, 3, Some(3))), dbInt.pathMap.get())
     assertEquals(dbInt.getActorRef(p2), Some((Nil, 2, Some(1))), dbInt.pathMap.get())
     assertEquals(dbInt.getActorRef(p1), Some((Nil, 1, Some(1))), dbInt.pathMap.get())

     dbInt.removePath(p3)
     assertEquals(dbInt.getActorRef(p3), Some((List("test"), 2, Some(1))), dbInt.pathMap.get())
     assertEquals(dbInt.getActorRef(p2), Some((Nil, 2, Some(1))), dbInt.pathMap.get())
     assertEquals(dbInt.getActorRef(p1), Some((Nil, 1, Some(1))), dbInt.pathMap.get())

     dbInt.removePath(p2)
     assertEquals(dbInt.getActorRef(p3), Some((List("src", "test"), 1, Some(1))),
       dbInt.pathMap.get())
     assertEquals(dbInt.getActorRef(p2), Some((List("src"), 1, Some(1))), dbInt.pathMap.get())
     assertEquals(dbInt.getActorRef(p1), Some((Nil, 1, Some(1))), dbInt.pathMap.get())

     dbInt.removePath(p1)
     assertEquals(dbInt.getActorRef(p3), None, dbInt.pathMap.get())
     assertEquals(dbInt.getActorRef(p2), None, dbInt.pathMap.get())
     assertEquals(dbInt.getActorRef(p1), None, dbInt.pathMap.get())

     // in hit example attr "" mean no acl
     dbInt.addActorRef(p1, 1, "")
     val rbis: Option[(List[String], Int, Option[Int])] = dbInt.getActorRef(p1)
     assertEquals(rbis, Some((List[String](), 1, None)))

     dbInt.addActorRef(p2, 2, "")
     val r2bis = dbInt.getActorRef(p2)
     assertEquals(r2bis, Some((Nil, 2, None)), dbInt.pathMap.get())
   }
   type DT2 = DirTree[Int, Boolean]

   test("Boolean Attr") {
     val rt1: DT2 = DirTree(1, true) // we should always have an acl at the root!
     val rt1_     = rt1.insert(1, Nil, true)
     assertEquals(rt1, rt1_)

     val rt1_2 = rt1.insert(11, Nil, false)
     assertEquals(rt1_2, DirTree(11, false))

     val rt1_3 = rt1.replace(false, Nil)
     assertEquals(rt1_3, DirTree(1, false))

     // the insertion is ignored
     val rt1_4: DT2 = rt1.insert(2, srcTst, false)
     assertEquals(rt1_4, rt1)
     assertEquals(rt1_4.findClosest(Nil), (Nil, 1, true))
     assertEquals(rt1_4.findClosest(src), (src, 1, true))

     val rt2: DT2 = rt1.insert(2, src, false)
     assertEquals(rt2, DirTree(1, true, HashMap("src" -> DirTree(2, false))))
     assertEquals(rt2.findClosest(src), (Nil, 2, false))
     assertEquals(rt2.findClosestRs(src) { identity }, (Nil, 2, Some(1)))

     val rt3 = rt2.insert(3, srcTst, true)
     assertEquals(rt3.findClosest(srcTst), (Nil, 3, true), rt3)
     assertEquals(rt3.findClosest(src), (Nil, 2, false), rt3)
     assertEquals(rt3.findClosest(srcTstScHello), (srcTstScHello.drop(2), 3, true), rt3)
     assertEquals(rt3,
       DirTree(1, true,
         HashMap("src" ->
           DirTree(2, false,
             HashMap("test" ->
               DirTree(3, true))))))
     assertEquals(rt3.findClosestRs(srcTst) { identity }, (Nil, 3, Some(3)))
     assertEquals(rt3.findClosestRs(src) { identity }, (Nil, 2, Some(1)))
     assertEquals(rt3.findClosestRs(srcTsSc) { identity }, (srcTsSc.drop(2), 3, Some(3)))
     assertEquals(rt3.findClosestRs(srcTstScHello) { identity },
       (srcTstScHello.drop(2), 3, Some(3)))
   }

   test("stack safety") {
     val root: DT2         = DirTree(0, true)
     val values: Seq[Int]  = 1 to 5000
     val path: Seq[String] = values.map(_.toString)
     val dts: List[DT2] =
       values.toList.foldLeft(List(root)) { (dts, i) =>
         dts.head.insert(i, path.take(i).toList, i % 2 == 0) :: dts
       }.reverse
//     // test a few values in dts
     val dt1 = dts(1)
     assertEquals(dt1, DirTree(0, true, HashMap("1" -> DirTree(1, false))))
     val dt2 = dts(2)
     assertEquals(dt2,
       DirTree(0, true,
         HashMap("1" ->
           DirTree(1, false, HashMap("2" -> DirTree(2, true))))))
     assertEquals(dts(10).findClosest(path.take(9).toList), (Nil, 9, false))
     assertEquals(dts(10).findClosest(path.take(11).toList), (List("11"), 10, true))
     assertEquals(dts(10).findClosest(path.take(12).toList), (List("11", "12"), 10, true))
     val dt2000 = dts(2000)
     assertEquals(dt2000.findClosest(path.take(1999).toList), (Nil, 1999, false))
     assertEquals(dt2000.findClosest(path.take(1000).toList), (Nil, 1000, true))
     val dt4990: DT2 = dts(4990)
     assertEquals(dt4990.findClosest(path.take(1999).toList), (Nil, 1999, false))
     assertEquals(dt4990.findClosest(path.take(1000).toList), (Nil, 1000, true))
     assertEquals(dt4990.findClosest(path.take(4990).toList), (Nil, 4990, true))
     assertEquals(dt4990.findClosest(path.take(4991).toList), (List("4991"), 4990, true))
     assertEquals(dt4990.findClosest(path.take(4992).toList), (List("4991", "4992"), 4990, true))
     val dt4999        = dts(4999)
     val dt4991Deleted = dt4999.delete(path.take(4991).toList)
     assert(dt4991Deleted.isDefined)
     assertEquals(dt4991Deleted.get, dt4990)
     val dt4991path = path.take(4991)
     assertEquals(dt4991Deleted.get.replace(false, dt4991path.toList), dts(4991))
   }
