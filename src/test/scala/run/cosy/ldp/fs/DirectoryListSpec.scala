/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp.fs

import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{Graph, SourceShape}
import akka.stream.stage.GraphStage
import akka.stream.testkit.{TestPublisher, TestSubscriber}
import akka.testkit.AkkaSpec
import akka.stream.stage
import akka.{NotUsed, stream}
import scala.concurrent.duration.*

import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import scala.concurrent.Await

class DirectoryListSpec extends AkkaSpec:

   "Walk source directory" in {
     import akka.stream.Attributes
     import akka.stream.Outlet
     import akka.stream.SourceShape
     import akka.stream.stage.GraphStage
     import akka.stream.stage.GraphStageLogic
     import akka.stream.stage.OutHandler

     val sourceGraph = DirectoryList(Path.of("test"), depth = 2)()
     val result = Source.fromGraph(sourceGraph).runForeach { (p: Path, att: BasicFileAttributes) =>
       // todo
     }

     Await.result(result, 10.seconds)
   }
