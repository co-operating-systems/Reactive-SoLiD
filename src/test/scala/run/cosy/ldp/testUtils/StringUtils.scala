/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp.testUtils

import akka.http.scaladsl.model.{
  ContentType,
  ContentTypes,
  HttpEntity,
  HttpMessage,
  HttpRequest,
  HttpResponse
}

import java.util.Base64
import scala.IArray
import scala.annotation.tailrec

object StringUtils:
   extension (msg: HttpMessage)
     def documented: String =
        val l: List[String] = msg match
           case req: HttpRequest =>
             import req.*
             s"${method.value} $uri ${protocol.value}" :: {
               for h <- headers.toList yield s"${h.name}: ${h.value}"
             }
           case res: HttpResponse =>
             import res.*
             protocol.value + " " + status.value :: {
               for h <- headers.toList yield s"${h.name}: ${h.value}"
             }
        import msg.*
        val ct = entity match
           case HttpEntity.Empty => List()
           case e: HttpEntity if e.contentType != ContentTypes.NoContentType =>
             ("Content-Type: " + e.contentType) ::
               e.contentLengthOption.map("Content-Length: " + _).toList
           case _ => List()
        (l ::: ct).mkString("\n")
