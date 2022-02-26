/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp

import munit.*
import munit.Location.*
import run.cosy.ldp.ResourceRegistry.*
import akka.http.scaladsl.model.Uri
import run.cosy.Solid.pathToList

class PathDBInt extends PathDB[Int]

class TestRegistry extends munit.FunSuite:
   val dbInt         = new PathDBInt()
   val srcTstScHello = List("src", "test", "scala", "hello.txt")
   val srcTst        = srcTstScHello.take(2)
   val src           = srcTst.take(1)
   val srcTsSc       = srcTstScHello.take(3)

   // low level tests
   test("On ATree[Int]") {
     val rt1 = ATree(1)

     val rt1_ = rt1.insert(1, Nil)
     assert(rt1 eq rt1_)

     val rt2 = rt1.insert(2, srcTst)
     assertEquals(rt2.findClosest(Nil), (Nil, 1))
     assertEquals(rt2.findClosest(src), (src, 1))

     val rt3 = rt1.insert(2, src)
     assertEquals(rt3.findClosest(src), (Nil, 2))

     val rt4 = rt3.insert(3, srcTst)
     assertEquals(rt4.findClosest(srcTst), (Nil, 3), rt4)
     assertEquals(rt4.findClosest(src), (Nil, 2), rt4)
     assertEquals(rt4.findClosest(srcTstScHello), (srcTstScHello.drop(2), 3), rt4)

     val rt5 = rt4.insert(4, srcTsSc)
     assertEquals(rt5.findClosest(src), (Nil, 2), rt5)
     assertEquals(rt5.findClosest(srcTstScHello), (srcTstScHello.drop(3), 4), rt5)

     val rt6 = rt5.insert(5, srcTstScHello)
     assertEquals(rt6.findClosest(srcTstScHello), (Nil, 5), rt6)

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
