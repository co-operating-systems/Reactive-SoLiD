/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp

import akka.http.scaladsl.model.Uri
import run.cosy.RDF
import run.cosy.ldp.SolidCmd.*

import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

case class TestCompiler(val ws: TestServer):

   import RDF.*
   import RDF.ops.*
   import cats.arrow.FunctionK
   import cats.{Id, Now, catsInstancesForId, ~>}

   import akka.http.scaladsl.model.StatusCodes

   given ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

   def eval: SolidCmd ~> Id = new (SolidCmd ~> Id):
      def apply[A](cmd: SolidCmd[A]): Id[A] = cmd match
      case Get(url, f) => ws.absDB.get(url) match
        case Some(g) => f(Response(Meta(url, StatusCodes.OK, Seq()), Success(g)))
        // todo: Create an exception for this that can be re-used
        case None => f(Response(
            Meta(url, StatusCodes.NotFound, Seq()),
            Failure(new Exception("no content"))
          ))
      case Plain(_, _) => ??? // todo: built up an example with Plain contents
      case Wait(future, uri, k) =>
        Await.result(future.transform(atry => Success(k(atry))), Duration(20, TimeUnit.SECONDS))
