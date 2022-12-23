/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp.fs

import _root_.akka.actor.testkit.typed.CapturedLogEvent
import _root_.akka.actor.testkit.typed.Effect.*
import _root_.akka.actor.testkit.typed.scaladsl.{ActorTestKit, BehaviorTestKit, TestInbox}
import _root_.akka.actor.typed.Behavior
import _root_.akka.actor.typed.ActorRef
import _root_.akka.actor.typed.scaladsl
import _root_.akka.http.scaladsl.model.HttpMethods.{GET, POST}
import _root_.akka.http.scaladsl.model.Uri.Path
import _root_.akka.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpRequest,
  MediaRange,
  MediaRanges,
  MediaTypes
}
import run.cosy.ldp.{Messages, ResourceRegistry, SolidCmd}
import run.cosy.ldp.fs.BasicContainer

import java.nio.file.Files
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AnyFlatSpec
import org.slf4j.event.Level
import run.cosy.http.headers.Slug
import _root_.akka.testkit.TestKit
import _root_.akka.actor.ActorSystem
import _root_.akka.http.scaladsl.model.StatusCodes.{Created, OK}
import _root_.akka.http.scaladsl.model.headers.{Accept, Location}
import _root_.akka.stream.{ActorMaterializer, Materializer}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import akka.http.scaladsl.model.Uri
import run.cosy.http.auth.WebServerAgent
import run.cosy.ldp.testUtils.TmpDir
import run.cosy.ldp.Messages.CmdMessage

import java.nio.file
import scalaz.NonEmptyList

class TestContainerSpec extends AnyFlatSpec with BeforeAndAfterAll with Matchers:

   def tmpDir(testCode: file.Path => Any): Unit =
      val dir: file.Path = TmpDir.createDir("solidTest")
      try
        testCode(dir) // "loan" the fixture to the test
      finally TmpDir.deleteDir(dir)
   end tmpDir

   val testKit = ActorTestKit()

   "Root Container" should "be started" in tmpDir { dirPath =>

      import akka.http.scaladsl.model.HttpResponse
      val rootUri = Uri("http://localhost:8080")

      given registry: ResourceRegistry                 = ResourceRegistry(testKit.system)
      val rootCntr: Behavior[BasicContainer.AcceptMsg] = BasicContainer(rootUri, dirPath)
      val rootActr: ActorRef[BasicContainer.AcceptMsg] = testKit.spawn(rootCntr, "solid")
      val probe                                        = testKit.createTestProbe[HttpResponse]()
      given classic: ActorSystem                       = testKit.system.classicSystem
      given ec: ExecutionContext                       = testKit.system.executionContext

      {
        // create Hello
        import _root_.run.cosy.akka.http.headers.Encoding.{given, *}
        rootActr ! Messages.Do(CmdMessage(
          SolidCmd.plain(
            HttpRequest(
              POST,
              rootUri.withPath(Uri.Path.SingleSlash),
              Seq(Slug("Hello".asClean)),
              HttpEntity(ContentTypes.`text/plain(UTF-8)`, "Hello World")
            )
          ),
          WebServerAgent,
          probe.ref
        ))

        val HttpResponse(status, hdrs, response, protocol) = probe.receiveMessage(): @unchecked
        assert(status == Created)
        assert(hdrs.contains(Location(rootUri.withPath(Uri.Path.Empty / ("Hello")))))
        assert(response.contentType.mediaType == MediaTypes.`text/plain`)
      }
      {
        // Read Hello
        rootActr ! Messages.RouteMsg(
          NonEmptyList("Hello"),
          CmdMessage(
            SolidCmd.plain(
              HttpRequest(
                GET,
                rootUri.withPath(Uri.Path / "Hello"),
                Seq(Accept(MediaRanges.`*/*`))
              )
            ),
            WebServerAgent,
            probe.ref
          )
        )
        val HttpResponse(status, hdrs, response, protocol) = probe.receiveMessage(): @unchecked
        assert(status == OK)
        //	assert(hdrs.contains(Location(rootUri.withPath(Uri.Path.Empty / ("Hello.txt")))))
        assert(response.contentType.mediaType == MediaTypes.`text/plain`)
        val f: Future[String] =
          response.httpEntity.dataBytes.runFold("")((s, bs) => s + bs.decodeString("UTF-8"))
        f.onComplete {
          case Success(str) => assert(str == "Hello World")
          case Failure(e)   => assert(false)
        }
      }

   }

   override def afterAll(): Unit = testKit.shutdownTestKit()
