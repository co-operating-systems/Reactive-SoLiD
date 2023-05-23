/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp.fs

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.HttpMethods.{GET, HEAD, OPTIONS, PUT}
import akka.http.scaladsl.model.headers.{Accept, Allow, Link, LinkParams, LinkValue}
import org.eclipse.rdf4j.model.impl.DynamicModel
import org.eclipse.rdf4j.rio.{RDFFormat, RDFWriter, Rio}
import run.cosy.RDF.Rdf
import run.cosy.http.RDFMediaTypes.{`application/trig`, `text/turtle`}
import run.cosy.http.RdfParser
import run.cosy.ldp.{ACInfo, SolidPostOffice}
import run.cosy.ldp.Messages.CmdMessage
import run.cosy.ldp.SolidCmd.Plain
import run.cosy.ldp.fs.BasicContainer.PostCreation
import run.cosy.ldp.fs.Resource.AcceptMsg
import run.cosy.ldp.ACInfo.*

import java.io.ByteArrayOutputStream
import java.nio.file.{CopyOption, Files, Path as FPath}

object ACResource:
   val AllowHeader = Allow(GET, PUT, HEAD, OPTIONS)
   // todo: I would like the server to be able to specify whic mime types it can serve a resource in
   //  there does not seem to be a simple way to do that.
   //  [[https://httpwg.org/http-extensions/draft-ietf-httpbis-variants.html Http-bis variants]]
   //  we would like to be able to specify that trig, n3 and other formats are also ok.
   val VersionHeaders = AllowHeader :: Nil

   def apply(containerUri: Uri, acrUri: Uri, linkPath: FPath, name: String, forDir: Boolean): Behavior[AcceptMsg] = Behaviors
     .setup[AcceptMsg] { (context: ActorContext[AcceptMsg]) =>
       import run.cosy.http.util.UriX.*
       // note the behavior of an ACResource always takes itself as the ACR
       // but it could also be different. An ACL could have another ACL...
       // see https://github.com/solid/authorization-panel/issues/189
       // Still at present this is only done so it fits in with ResourceTrait
       new ACResource(acrUri, linkPath, context)
         .behavior(ACtrlRef(containerUri, name, context.self, forDir))
     }

/** Actor for Access Control resources, currently ".acl"
  */
class ACResource(uri: Uri, path: FPath, context: ActorContext[AcceptMsg])
    extends ResourceTrait(uri, path, context):

   import run.cosy.RDF.{*, given}
   import run.cosy.ldp.fs.Resource.{AcceptMsg, LDPR}
   // For container managed resources these are not appicable.

   override def fileSystemProblemBehavior(e: Exception): Behaviors.Receive[AcceptMsg] = ???

   override def aclLinks(acl: Uri, active: ACInfo): List[LinkValue] =
    List(LinkValue(acl, LinkParams.rel("acl")))

/** This does not apply (so probably should not be here) */
   override def archivedBehavior(linkedToFile: String): Option[Behaviors.Receive[AcceptMsg]] = None

   // Then we have the default behavior
   override def linkDoesNotExistBehavior: Behaviors.Receive[AcceptMsg] = defaultBehavior

   // We should adopt default behavior perhaps`
   @throws[SecurityException]
   override def linkedToFileDoesNotExist(
       linkTo: String,
       acinfo: ACInfo
   ): Option[Behaviors.Receive[AcceptMsg]] =
     if !Files.exists(path.resolveSibling(linkTo)) then Some(defaultBehavior)
     else None

   // Default behavior is the same as normal behavior, except that a GET on the resource returns the default
   // representation amd a PUT does not first check the file to get its properties
   def defaultBehavior: Behaviors.Receive[AcceptMsg] = VersionsInfo(0, path, Root(uri))
     .NormalBehavior

   /** Difference with superclass:
     *   - the Version 0 of an acl resource returns the default graph and a PUT on it does not check
     *     the file attributes
     *   - A request for NTrig returns the closure of the :imports relations of the resources
     */
   def VersionsInfo(lastVersion: Int, linkTo: FPath, acinfo: ACInfo): VersionsInfo =
     // todo: the ACRef is not used. of the ACL resource is itself
     // if an ACR could have another ACR then that would need to be written somewhere.
     new VersionsInfo(lastVersion, linkTo, acinfo):
        import run.cosy.ldp.SolidCmd
        import run.cosy.ldp.fs.BasicContainer.AcceptMsg

        override def doPlainGet[A](
            cmdmsg: CmdMessage[A],
            req: HttpRequest,
            f: HttpResponse => SolidCmd.Script[A]
        ): Behavior[Resource.AcceptMsg] =
           import run.cosy.ldp.SolidCmd.ReqDataSet
           val mediaRanges: Seq[MediaRange] = req.headers[Accept].flatMap(_.mediaRanges)

           def injectDataSetResponse(dataSetReq: ReqDataSet): SolidCmd.Script[A] =
              import cats.free.Cofree
              import org.apache.jena.query.Dataset
              import run.cosy.ldp.SolidCmd.{GraF, Meta}
              val dt: ReqDataSet = dataSetReq
              // we do this in 2 stages to later be optimised to 1 later
              // 1. we want to build a Map[Uri,Graph] from the Cofree structure

              val mapDS: Map[Uri, Rdf#Graph] = Cofree.cata[GraF, Meta, Map[Uri, Rdf#Graph]](dt) {
                (meta: Meta, grds: GraF[Map[Uri, Rdf#Graph]]) =>
                  cats.Now(grds.other.fold(Map()) { (m1, m2) =>
                    m1 ++ m2
                  } + (meta.url -> grds.graph))
              }.value

              import org.eclipse.rdf4j.model.impl.DynamicModelFactory
              import org.eclipse.rdf4j.model.{IRI, Model, Value, Resource as eResource}

              val model: DynamicModel = new DynamicModelFactory().createEmptyModel()
              mapDS.foreach { (name, graph) =>
                 val n: eResource = name.toRdf.asInstanceOf[eResource]
                 import ops.*
                 graph.triples.foreach { tr =>
                   tr.subject match
                    case sub: eResource =>
                      val pred: IRI = tr.predicate
                      val obj: Value = tr.objectt
                      model.add(sub, pred, obj, n)
                    case _ =>
                 // todo: otherwise we have a literal in subject position.
                 // deal with this when moving to banana-rdf
                 }
              }

              val out = new ByteArrayOutputStream(524)
              val wr: RDFWriter = Rio.createWriter(RDFFormat.TRIG, out)
              import org.eclipse.rdf4j.rio.RDFHandlerException

              try
                 wr.startRDF
                 import scala.jdk.CollectionConverters.{*, given}
                 for st <- model.iterator().asScala do wr.handleStatement(st)
                 wr.endRDF
              catch
                case e: RDFHandlerException => // todo oh no, do something!
              finally out.close
              f(
                HttpResponse(
                  StatusCodes.OK,
                  Seq(),
                  HttpEntity(`application/trig`.toContentType, new String(out.toByteArray))
                )
              )

           /* Jena version of how to write out TriG todo: abstract and move to banana-rdf

				import org.apache.jena.query.{Dataset, ReadWrite}
				import org.apache.jena.riot.{Lang, RDFWriter}
				import org.apache.jena.sparql.core.DatasetGraph
				import org.apache.jena.tdb2.TDB2Factory
				//2. let us build a Jena DataSet Graph and turn that to a response

				import org.apache.jena.sparql.core._
				import run.cosy.RDF._
				val dsg = DatasetGraphFactory.create()
				mapDS.foreach((u, g) => dsg.addGraph(u.toRdf, g))

				//todo: fill in headers when this is shown to work
				//Next. we return the DataSet in NQuads format
				val writer = RDFWriter.create().source(dsg).lang(Lang.TRIG).build()
				f(HttpResponse(StatusCodes.OK, Seq(),
					HttpEntity(`application/trig`.toContentType, new String(out.toByteArray))))
            */
           end injectDataSetResponse

           if mediaRanges.exists(_.matches(`application/trig`)) then // todo: select top ones first
              // todo: we may also want to restrict nquad behavior to the latest version, where it makes sense
              import run.cosy.ldp.SolidCmd
              import run.cosy.ldp.SolidCmd.{ReqDataSet, Script}
              // we need to change the cmdMessage to
              // 1. build the imports closure
              val res: Script[A] =
                for
                   dataSetReq <- SolidCmd.fetchWithImports(req.uri)
                   x <- injectDataSetResponse(dataSetReq)
                yield x
              cmdmsg.continue(res)
           else cmdmsg.respondWith(plainGet(req))
           Behaviors.same

        override def Put(cmd: CmdMessage[?]): Unit =
          if lastVersion == 0 then
             cmd.commands match
              case p @ Plain(req, k) =>
                if req.entity.contentType.mediaType == `text/turtle` then
                   val linkToPath: FPath = versionFPath(1, `text/turtle`.fileExtensions.head)
                   PUTstarted = true
                   BuildContent(PostCreation(linkToPath, cmd))
                else
                   cmd.respondWith(
                     HttpResponse(
                       StatusCodes.NotAcceptable,
                       entity = HttpEntity("we only accept Plain HTTP Put")
                     )
                   )
              case _ => cmd.respondWith(
                  HttpResponse(
                    StatusCodes.NotImplemented,
                    entity = HttpEntity("we don't yet deal with PUT for " + cmd.commands)
                  )
                )
          else super.Put(cmd)
        end Put

   end VersionsInfo
end ACResource
