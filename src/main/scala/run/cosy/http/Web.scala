/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http

import Web.PGWeb
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import model.{ContentType, HttpHeader, HttpRequest, HttpResponse, StatusCode, Uri}
import org.apache.jena.graph.Graph
import org.w3.banana.PointedGraph
import run.cosy.RDF.{given, *}
import akka.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

object Web:
   type PGWeb = IResponse[PointedGraph[Rdf]]

   extension (uri: Uri)
     def toRdf: Rdf#URI = ops.URI(uri.toString)

// Need to generalise this, so that it can fetch locally and from the web
class Web(using val ec: ExecutionContext, val as: ActorSystem[Nothing]):

   def GETRdfDoc(uri: Uri, maxRedirect: Int = 4): Future[HttpResponse] =
     GET(RdfParser.rdfRequest(uri), maxRedirect).map(_._1)

   // todo: add something to the response re number of redirects
   // see: https://github.com/akka/akka-http/issues/195
   def GET(
       req: HttpRequest,
       maxRedirect: Int = 4,
       history: List[ResponseSummary] = List()
       //		  keyChain: List[Sig.Client]=List()
   ): Future[(HttpResponse, List[ResponseSummary])] =
     try
        import model.StatusCodes.{Success, Redirection}
        Http().singleRequest(req)
          .recoverWith { case e => Future.failed(ConnectionException(req.uri.toString, e)) }
          .flatMap { resp =>
             def summary =
               ResponseSummary(req.uri, resp.status, resp.headers, resp.entity.contentType)

             resp.status match
              case Success(_) => Future.successful((resp, summary :: history))
              case Redirection(_) => {
                resp.header[model.headers.Location].map { loc =>
                   val newReq = req.withUri(loc.uri)
                   resp.discardEntityBytes()
                   if maxRedirect > 0 then
                      GET(newReq, maxRedirect - 1, summary :: history)
                   else Http().singleRequest(newReq).map((_, summary :: history))
                }.getOrElse(Future.failed(HTTPException(
                  summary,
                  s"Location header not found on ${resp.status} for ${req.uri}"
                )))
              }
//todo later: deal with authorization on remote resources
//							case Unauthorized  => {
//								import akka.http.scaladsl.model.headers.{`WWW-Authenticate`,Date}
//								val date = Date(akka.http.scaladsl.model.DateTime.now)
//								val reqWithDate = req.addHeader(date)
//								val tryFuture = for {
//									wwa <- resp.header[`WWW-Authenticate`]
//										.fold[Try[`WWW-Authenticate`]](
//											Failure(HTTPException(summary,"no WWW-Authenticate header"))
//										)(scala.util.Success(_))
//									headers <- Try { Sig.Client.signatureHeaders(wwa).get } //<- this should always succeed
//									client <- keyChain.headOption.fold[Try[Sig.Client]](
//										Failure(AuthException(summary,"no client keys"))
//									)(scala.util.Success(_))
//									authorization <- client.authorize(reqWithDate,headers)
//								} yield {
//									GET(reqWithDate.addHeader(authorization), maxRedirect, summary::history, keyChain.tail)
//								}
//								Future.fromTry(tryFuture).flatten
//							}
              case _ => {
                resp.discardEntityBytes()
                Future.failed(StatusCodeException(summary))
              }
          }
     catch
        case NonFatal(e) => Future.failed(ConnectionException(req.uri.toString, e))
   end GET

   def GETrdf(uri: Uri): Future[IResponse[Rdf#Graph]] =
     GETRdfDoc(uri).flatMap(RdfParser.unmarshalToRDF(_, uri))

   def pointedGET(uri: Uri): Future[PGWeb] =
      import Web.*
      GETrdf(uri).map(_.map(PointedGraph[Rdf](uri.toRdf, _)))

/** Interpreted HttpResponse, i.e. the interpretation of a representation
  */
case class IResponse[C](
    origin: Uri,
    status: StatusCode,
    headers: Seq[HttpHeader],
    fromContentType: ContentType,
    content: C
):
   def map[D](f: C => D) = this.copy(content = f(content))
