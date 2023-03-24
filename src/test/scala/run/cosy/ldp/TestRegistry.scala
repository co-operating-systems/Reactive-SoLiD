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
import run.cosy.ldp.ResourceRegistry.*

import scala.collection.immutable.HashMap

class PathDBInt extends PathDB[Int]

class TestRegistry extends munit.FunSuite:
   val dbInt         = new PathDBInt()
   val srcTstScHello = List("src", "test", "scala", "hello.txt")
   val srcTst        = srcTstScHello.take(2)
   val src           = srcTst.take(1)
   val srcTsSc       = srcTstScHello.take(3)

   // low level tests
   test("On ATree[Int]") {
     val rt1 = DirTree(1)

     val rt1_ = rt1.insert(1, Nil)
     assertEquals(rt1, rt1_)

     val rt1bis = rt1.insert(11, Nil)
     assertEquals(rt1bis, DirTree(11))

     // the insertion is ignored
     val rt2 = rt1.insert(2, srcTst)
     assertEquals(rt2, rt1)

     assertEquals(rt2.findClosest(Nil), (Nil, 1))
     assertEquals(rt2.findClosest(src), (src, 1))

     assertEquals(rt1.toClosestAPath(List("src")), (Left(Nil), List(("src", rt1))))

     val rt3 = rt1.insert(2, src)
     assertEquals(rt3, DirTree(1, HashMap("src" -> DirTree(2))))
     assertEquals(rt3.findClosest(src), (Nil, 2))

     val rt4 = rt3.insert(3, srcTst)
     assertEquals(rt4.findClosest(srcTst), (Nil, 3), rt4)
     assertEquals(rt4.findClosest(src), (Nil, 2), rt4)
     assertEquals(rt4.findClosest(srcTstScHello), (srcTstScHello.drop(2), 3), rt4)
     assertEquals(rt4, DirTree(1, HashMap("src" -> DirTree(2, HashMap("test" -> DirTree(3))))))

//     assertEquals(rt4.toClosestAPath(srcTst),null)
     val rt4new = rt4.replace(55, List("src", "test"))
     assertEquals(rt4new, DirTree(1, HashMap("src" -> DirTree(2, HashMap("test" -> DirTree(55))))))

     val rt5 = rt4.insert(4, srcTsSc)
     assertEquals(rt5.findClosest(src), (Nil, 2), rt5)
     assertEquals(rt5.findClosest(srcTstScHello), (srcTstScHello.drop(3), 4), rt5)

     val rt6 = rt5.insert(5, srcTstScHello)
     assertEquals(rt6.findClosest(srcTstScHello), (Nil, 5), rt6)

//     assertEquals(rt6.toClosestAPath(srcTsSc),null)
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
     val p1 = Uri.Path("/")
     dbInt.addActorRef(p1, 1)
     val r = dbInt.getActorRef(p1)
     assertEquals(r, Some((List[String](), 1)))

     val p2 = Uri.Path("/src")
     dbInt.addActorRef(p2, 2)
     val r2 = dbInt.getActorRef(p2)
     assertEquals(r2, Some(Nil, 2), dbInt.pathMap.get())

     val p3 = Uri.Path("/src/test/")
     dbInt.addActorRef(p3, 3)
     assertEquals(dbInt.getActorRef(p3), Some((Nil, 3)), dbInt.pathMap.get())
     assertEquals(dbInt.getActorRef(p2), Some((Nil, 2)), dbInt.pathMap.get())
     assertEquals(dbInt.getActorRef(p1), Some((Nil, 1)), dbInt.pathMap.get())

     dbInt.removePath(p3)
     assertEquals(dbInt.getActorRef(p3), Some((List("test"), 2)), dbInt.pathMap.get())
     assertEquals(dbInt.getActorRef(p2), Some((Nil, 2)), dbInt.pathMap.get())
     assertEquals(dbInt.getActorRef(p1), Some((Nil, 1)), dbInt.pathMap.get())

     dbInt.removePath(p2)
     assertEquals(dbInt.getActorRef(p3), Some((List("src", "test"), 1)), dbInt.pathMap.get())
     assertEquals(dbInt.getActorRef(p2), Some((List("src"), 1)), dbInt.pathMap.get())
     assertEquals(dbInt.getActorRef(p1), Some((Nil, 1)), dbInt.pathMap.get())

     dbInt.removePath(p1)
     assertEquals(dbInt.getActorRef(p3), None, dbInt.pathMap.get())
     assertEquals(dbInt.getActorRef(p2), None, dbInt.pathMap.get())
     assertEquals(dbInt.getActorRef(p1), None, dbInt.pathMap.get())

   }

   test("stack safety") {
     val root              = DirTree(0)
     val values: Seq[Int]  = 1 to 5000
     val path: Seq[String] = values.map(_.toString)
     val dts: List[DirTree[Int]] =
       values.toList.foldLeft(List(root)) { (dts, i) =>
         dts.head.insert(i, path.take(i).toList) :: dts
       }.reverse
     // test a few values in dts
     val dt1 = dts(1)
     assertEquals(dt1, DirTree(0, HashMap("1" -> DirTree(1))))
     val dt2 = dts(2)
     assertEquals(dt2, DirTree(0, HashMap("1" -> DirTree(1, HashMap("2" -> DirTree(2))))))
     assertEquals(dts(10).findClosest(path.take(9).toList), (Nil, 9))
     assertEquals(dts(10).findClosest(path.take(11).toList), (List("11"), 10))
     assertEquals(dts(10).findClosest(path.take(12).toList), (List("11", "12"), 10))
     val dt2000 = dts(2000)
     assertEquals(dt2000.findClosest(path.take(1999).toList), (Nil, 1999))
     assertEquals(dt2000.findClosest(path.take(1000).toList), (Nil, 1000))
     val dt4990 = dts(4990)
     assertEquals(dt4990.findClosest(path.take(1999).toList), (Nil, 1999))
     assertEquals(dt4990.findClosest(path.take(1000).toList), (Nil, 1000))
     assertEquals(dt4990.findClosest(path.take(4990).toList), (Nil, 4990))
     assertEquals(dt4990.findClosest(path.take(4991).toList), (List("4991"), 4990))
     assertEquals(dt4990.findClosest(path.take(4992).toList), (List("4991", "4992"), 4990))
     val dt4999        = dts(4999)
     val dt4991Deleted = dt4999.delete(path.take(4991).toList)
     assert(dt4991Deleted.isDefined)
     assertEquals(dt4991Deleted.get, dt4990)
   }
