/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.{MediaRange, MediaTypes}
import akka.http.scaladsl.model.MediaTypes.extensionMap
import akka.http.scaladsl.settings.ParserSettings

/** Media Types for RDF. Collected from the
  * [[https://docs.aws.amazon.com/neptune/latest/userguide/sparql-media-type-support.html RDF media types used by SPARQL in Neptune]]
  * document by Amazon. Feel free to complete the list if needed.
  */
object RDFMediaTypes:

   import akka.http.scaladsl.model.{MediaType, Uri}
   import akka.http.scaladsl.model.HttpCharsets.{`UTF-8`, `US-ASCII`}

   /** see [[https://www.w3.org/TR/rdf-syntax-grammar/#section-MIME-Type RDF1.1 XML Syntax]] spec
     * and [[https://tools.ietf.org/html/rfc3870 RFC3870]] registration
     */
   val `application/rdf+xml` = MediaType.customWithOpenCharset(
     "application",
     "rdf+xml",
     fileExtensions = List("rdf")
   )

   // Note that around 2004 the W3C defined a procedure for Mime Type Registration
   // that uses the W3C specs without needing to go through an RFC procedure
   // see [[https://www.w3.org/2020/01/registering-mediatypes.html Register and Internet Media Types for a W3C Spec]]

   /** see [[https://www.w3.org/TR/n-triples/#n-triples-mediatype RDF 1.1 N-Triples]] spec. */
   val `application/n-triples` = MediaType.customWithFixedCharset(
     "application",
     "n-triples",
     `UTF-8`,
     fileExtensions = List("nt")
   )

   /** see [[https://www.w3.org/TR/n-quads/#sec-mediatype W3C NQuads Speec]] has IRI:
     * http://www.w3.org/ns/formats/N-Quads
     */
   val `application/n-quads` = MediaType.customWithFixedCharset(
     "application",
     "n-quads",
     `UTF-8`,
     fileExtensions = List("nq")
   )

   /** The older version of n-quads was limited to US-ASCII and had a different mime type */
   val `text/n-quads` = MediaType.customWithFixedCharset(
     "text",
     "n-quads",
     `US-ASCII`,
     fileExtensions = List("nqAscii")
   )

   /** see [[https://www.w3.org/TR/turtle/ RDF 1.1 Turtle]] has URI:
     * http://www.w3.org/ns/formats/Turtle
     */
   val `text/turtle`: MediaType.WithFixedCharset = MediaType.customWithFixedCharset(
     "text",
     "turtle",
     `UTF-8`,
     fileExtensions = List("ttl")
   )

   /** see [[https://www.w3.org/TR/trig/#sec-mediaReg RDF1.1 TriG]]
     */
   val `application/trig` = MediaType.customWithFixedCharset(
     "application",
     "trig",
     `UTF-8`,
     fileExtensions = List("trig")
   )

   /** see
     * [[https://www.w3.org/TeamSubmission/n3/#sec-mediaReg Notation3 (N3): A readable RDF syntax]]
     */
   val `text/n3` = MediaType.customWithFixedCharset(
     "text",
     "n3",
     `UTF-8`,
     fileExtensions = List("n3")
   )

   /** see [[https://www.w3.org/TR/json-ld/#application-ld-json JSON-LD 1.1]] The JSON-LD spec
     * defined six values for a profile parameter listed next.
     */
   val `application/ld+json` = MediaType.customWithFixedCharset(
     "application",
     "ld+json",
     `UTF-8`,
     fileExtensions = List("jsonld")
   )

   val jsonLdExpandedProfile  = Uri("http://www.w3.org/ns/json-ld#expanded")
   val jsonLdCompactedProfile = Uri("http://www.w3.org/ns/json-ld#compacted")
   val jsonLdContextProfile   = Uri("http://www.w3.org/ns/json-ld#context")
   val jsonLdFlattenedProfile = Uri("http://www.w3.org/ns/json-ld#flattened")
   val jsonLdFrameProfile     = Uri("http://www.w3.org/ns/json-ld#frame")
   val jsonLdFramedProfile    = Uri("http://www.w3.org/ns/json-ld#framed")

   /** Quad format defined by HP in XML [[https://www.hpl.hp.com/techreports/2004/HPL-2004-56.html]]
     */
   val `application/trix` = MediaType.customWithOpenCharset(
     "application",
     "trix",
     fileExtensions = List("trix")
   )

   /** see
     * [[https://www.w3.org/TR/sparql11-results-json/#content-type SPARQL 1.1 Query Results JSON Format]]
     */
   val `application/sparql-results+json` = MediaType.customWithFixedCharset(
     "application",
     "sparql-results+json",
     `UTF-8`,
     fileExtensions = List("srj")
   )

   /** see
     * [[https://www.w3.org/TR/rdf-sparql-XMLres/#mime SPARQL Query Results XML Format (Second Edition)]]
     */
   val `application/sparql-results+xml` = MediaType.customWithOpenCharset(
     "application",
     "sparql-results+xml",
     fileExtensions = List("srx")
   )

   def rdfData: Seq[MediaType] = Seq(
     `application/rdf+xml`,
     `application/n-triples`,
     `application/n-quads`,
     `text/n-quads`,
     `text/turtle`,
     `application/trig`,
     `text/n3`,
     `application/ld+json`
   )

   def all: Seq[MediaType] = Seq(
     `application/rdf+xml`,
     `application/n-triples`,
     `application/n-quads`,
     `text/n-quads`,
     `text/turtle`,
     `application/trig`,
     `text/n3`,
     `application/ld+json`,
     `application/sparql-results+json`,
     `application/sparql-results+xml`,
     `application/trix`
   )

   object RDFData:
      def unapply(mr: MediaRange): Option[MediaType] = rdfData.find(mt => mr.matches(mt))
