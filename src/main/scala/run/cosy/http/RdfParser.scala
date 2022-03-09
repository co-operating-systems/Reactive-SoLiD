/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http

import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, PredefinedFromEntityUnmarshallers}
import akka.http.scaladsl.model.{
  ContentType,
  HttpEntity,
  HttpHeader,
  HttpRequest,
  HttpResponse,
  MediaRange,
  MediaType,
  ResponseEntity,
  StatusCode,
  StatusCodes,
  Uri
}
import akka.http.scaladsl.model.headers.Accept

import scala.concurrent.{ExecutionContext, Future}
import org.w3.banana.*
import org.w3.banana.syntax.*
import org.w3.banana.jena.Jena
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import org.apache.jena.graph.Graph
import org.w3.banana.io.{RDFReader, RDFWriter}
import run.cosy.ldp.fs.ACResource

import scala.util.{Failure, Success, Try}
import scala.util.control.NoStackTrace

object RdfParser:

   import run.cosy.RDF.{given, *}
   import ops.{given, *}
   import RDFMediaTypes.*

   val AcceptHeaders: Accept = Accept(
     `text/turtle`,
     `application/rdf+xml`,
     `application/n-triples`,
     `application/ld+json`.withQValue(0.8)
   )

   val AcceptHeadersDS: Accept = Accept(`application/n-quads`, `application/trig`)

   def rdfRequest(uri: Uri): HttpRequest =
     HttpRequest(uri = uri.withoutFragment)
       .addHeader(Accept(
         `text/turtle`,
         `application/rdf+xml`,
         `application/n-triples`,
         `application/ld+json`.withQValue(0.8) // todo: need to update parser in banana
         // `text/html`.withQValue(0.2)
       )) // we can't specify that we want RDFa in our markup

   def unmarshalToRDF(
       resp: HttpResponse,
       base: Uri
   )(using ExecutionContext, Materializer): Future[IResponse[Rdf#Graph]] =
      import resp.*
      given FromEntityUnmarshaller[Rdf#Graph] = RdfParser.rdfUnmarshaller(base)
      import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
      Unmarshal(entity).to[Rdf#Graph].map { g =>
        IResponse[Rdf#Graph](base, status, headers, entity.contentType, g)
      }

   /** @param base : the URI at which the document was resolved */
   def rdfUnmarshaller(base: Uri): FromEntityUnmarshaller[Rdf#Graph] =
     // todo: this loads everything into a string - bad
     PredefinedFromEntityUnmarshallers.stringUnmarshaller flatMapWithInput { (entity, string) ⇒
        def parseWith[T](reader: RDFReader[Rdf, Try, T]) = Future.fromTry {
          reader.read(new java.io.StringReader(string), base.toString)
        }
        // todo: use non blocking parsers
        entity.contentType.mediaType match
        case `text/turtle`           => parseWith(turtleReader)
        case `application/rdf+xml`   => parseWith(rdfXMLReader)
        case `application/n-triples` => parseWith(ntriplesReader)
        case `application/ld+json`   => parseWith(jsonldReader)
        // case `text/html` => new SesameRDFaReader()
        case _ => FastFuture.failed(MissingParserException(string.take(400)))
     }

   def highestPriortyRDFMediaType(mediaRanges: Seq[MediaRange]): Option[MediaType] =
      val sortedMR: Seq[MediaRange] = mediaRanges.sortBy(-_.qValue())
      sortedMR.collectFirst {
        case RDFMediaTypes.RDFData(mt) => mt
      }

   def toResponseEntity(graph: Rdf#Graph, mt: MediaType): Try[ResponseEntity] =
      import akka.http.scaladsl.model.HttpCharsets
      import akka.util.ByteString
      import akka.http.scaladsl.model.HttpEntity
      // todo: check the size of the graph, and return a streaming entity if graph is large enough (when async streams are available)
      def entity(writer: RDFWriter[Rdf, Try, ?]): Try[ResponseEntity] =
         val sTry = writer.asString(graph, None)
         sTry.map { str =>
           HttpEntity.Strict(ContentType(mt, () => HttpCharsets.`UTF-8`), ByteString(str))
         }
      mt match
      case `text/turtle`           => entity(turtleWriter)
      case `application/rdf+xml`   => entity(rdfXMLWriter)
      case `application/n-triples` => entity(ntriplesWriter)
      case `application/ld+json`   => entity(jsonldCompactedWriter)
      case _                       => Failure(new Exception(s"we don't have an RDF parser for $mt"))

   def response(graph: Rdf#Graph, mtypes: Seq[MediaRange]): HttpResponse =
      import akka.http.scaladsl.model.StatusCodes.OK
      import run.cosy.http.RdfParser.*
      val tryResp: Try[HttpResponse] =
        for
           highestMT <- highestPriortyRDFMediaType(mtypes)
             .fold(Failure(Exception("We only support RDF media types for this resource")))(
               Success(_)
             )
           response <- toResponseEntity(graph, highestMT)
        yield HttpResponse(OK, entity = response)
      tryResp match
      case Success(res) => res
      case Failure(res) =>
        HttpResponse(StatusCodes.NotAcceptable, entity = HttpEntity(res.toString))
