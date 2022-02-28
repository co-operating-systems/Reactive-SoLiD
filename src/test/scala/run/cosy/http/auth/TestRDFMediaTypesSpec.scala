/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http.auth

import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.MediaType.WithFixedCharset
import akka.http.scaladsl.model.*
import akka.http.scaladsl.server.Directives
import akka.stream.ActorMaterializer
import akka.testkit.*
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures
import run.cosy.http.RDFMediaTypes
import run.cosy.ldp.fs.Resource.extension

import scala.concurrent.duration.*

class CustomMediaTypesSpec extends AkkaSpec with ScalaFutures
    with Directives with RequestBuilding:

   "Http" should {
     "check RDFMedia types exist and if they differ in casing" in {
       val set = RDFMediaTypes.all

       set.contains(MediaType.parse("application/rdf+xml").toOption.get) should ===(true)
       set.contains(MediaType.parse("application/RDF+XML").toOption.get) should ===(true)
       set.contains(MediaType.parse("application/ld+json").toOption.get) should ===(true)
       set.contains(MediaType.parse("application/ld+JSON").toOption.get) should ===(true)
       set.contains(MediaType.parse("application/trig").toOption.get) should ===(true)
       set.contains(MediaType.parse("application/trix").toOption.get) should ===(true)
       set.contains(MediaType.parse("text/turtle").toOption.get) should ===(true)
       set.contains(MediaType.parse("text/n-quads").toOption.get) should ===(true)
     }

     "allow registering custom media type" in {
       import RDFMediaTypes.*
       import HttpCharsets.`UTF-8`
       import system.dispatcher
       // #application-custom

       // similarly in Java: `akka.http.javadsl.settings.[...]`
       import akka.http.scaladsl.settings.ParserSettings
       import akka.http.scaladsl.settings.ServerSettings

       info("Register RDF Media Types in running server")
       // add custom media type to parser settings:
       val parserSettings =
         ParserSettings.forServer(system).withCustomMediaTypes(RDFMediaTypes.all*)
       val serverSettings = ServerSettings(system).withParserSettings(parserSettings)

       val routes = extractRequest { r =>
         complete(r.entity.contentType.mediaType.fileExtensions.mkString(" "))
       }
       val binding = Http().newServerAt("localhost", 0).withSettings(serverSettings).bind(routes)
       // #application-custom

       def responseFor(entity: RequestEntity): String =
          val request = Get(s"http://localhost:${binding.futureValue.localAddress.getPort}/")
            .withEntity(entity)
          val response = Http().singleRequest(request).futureValue
          response.status should ===(StatusCodes.OK)
          response.toStrict(1.second.dilated).futureValue.entity.dataBytes
            .runFold(ByteString.empty)(_ ++ _).futureValue.utf8String

       responseFor(HttpEntity(`application/ld+json`, """{ "att" : "val"""")) should ===(
         "jsonld".toString
       )
       responseFor(
         HttpEntity(`application/rdf+xml`.withCharset(`UTF-8`), """<xml></xml>""")
       ) should ===("rdf".toString)

       info("test file extension for media types")
       {
         import MediaTypes.*
         MediaTypes.forExtension("txt") should ===(`text/plain`)
         MediaTypes.forExtensionOption("rdf") should ===(
           None
         ) // our custome types don't get registered :-(
       }
     }

   }
