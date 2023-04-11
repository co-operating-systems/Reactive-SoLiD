package run.cosy.ldp.fs

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.HttpMethods.{GET, HEAD, OPTIONS, PUT}
import akka.http.scaladsl.model.headers.{Accept, Allow, Link}
import org.eclipse.rdf4j.model.impl.DynamicModel
import org.eclipse.rdf4j.rio.{RDFFormat, RDFWriter, Rio}
import run.cosy.RDF.Rdf
import run.cosy.http.RDFMediaTypes.{`application/trig`, `text/turtle`}
import run.cosy.http.RdfParser
import run.cosy.ldp.Messages.CmdMessage
import run.cosy.ldp.SolidCmd.Plain
import run.cosy.ldp.fs.BasicContainer.PostCreation
import run.cosy.ldp.fs.Resource.AcceptMsg

import java.io.ByteArrayOutputStream
import java.nio.file.{CopyOption, Files, Path as FPath}

object ACResource:
   val AllowHeader = Allow(GET, PUT, HEAD, OPTIONS)
   // todo: I would like the server to be able to specify whic mime types it can serve a resource in
   //  there does not seem to be a simple way to do that.
   //  [[https://httpwg.org/http-extensions/draft-ietf-httpbis-variants.html Http-bis variants]]
   //  we would like to be able to specify that trig, n3 and other formats are also ok.
   val VersionHeaders = AllowHeader :: Nil

   def apply(rUri: Uri, linkName: FPath, name: String): Behavior[AcceptMsg] =
     Behaviors.setup[AcceptMsg] { (context: ActorContext[AcceptMsg]) =>
       // val exists = Files.exists(root)
       //			val registry = ResourceRegistry(context.system)
       //			registry.addActorRef(rUri.path, context.self)
       //			context.log.info("started LDPR actor at " + rUri.path)
       new ACResource(rUri, linkName, context).behavior
     }

/** Actor for Access Control resources, currently ".acl"
  * 
  */
class ACResource(uri: Uri, path: FPath, context: ActorContext[AcceptMsg])
    extends ResourceTrait(uri, path, context):

   import run.cosy.RDF.{*, given}
   import run.cosy.ldp.fs.Resource.{AcceptMsg, LDPR}
   // For container managed resources these are not appicable.

   override def fileSystemProblemBehavior(e: Exception): Behaviors.Receive[AcceptMsg] = ???

   /** This does not apply (so probably should not be here) */
   override def archivedBehavior(linkedToFile: String): Option[Behaviors.Receive[AcceptMsg]] = None

   // Then we have the default behavior
   override def linkDoesNotExistBehavior: Behaviors.Receive[AcceptMsg] = defaultBehavior

   // We should adopt default behavior perhaps`
   @throws[SecurityException]
   override def linkedToFileDoesNotExist(linkTo: String): Option[Behaviors.Receive[AcceptMsg]] =
     if !Files.exists(path.resolveSibling(linkTo)) then Some(defaultBehavior)
     else None

   // Default behavior is the same as normal behavior, except that a GET on the resource returns the default
   // representation amd a PUT does not first check the file to get its properties
   def defaultBehavior: Behaviors.Receive[AcceptMsg] = VersionsInfo(0, path).NormalBehavior

   /** Difference with superclass:
     *   - the Version 0 of an acl resource returns the default graph and a PUT on it does not check
     *     the file attributes
     *   - A request for NTrig returns the closure of the :imports relations of the resources
     */
   def VersionsInfo(lastVersion: Int, linkTo: FPath): VersionsInfo =
     new VersionsInfo(lastVersion, linkTo):
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

              val mapDS: Map[Uri, Rdf#Graph] =
                Cofree.cata[GraF, Meta, Map[Uri, Rdf#Graph]](dt) {
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
                      val pred: IRI  = tr.predicate
                      val obj: Value = tr.objectt
                      model.add(sub, pred, obj, n)
                    case _ =>
                 // todo: otherwise we have a literal in subject position.
                 // deal with this when moving to banana-rdf
                 }
              }

              val out           = new ByteArrayOutputStream(524)
              val wr: RDFWriter = Rio.createWriter(RDFFormat.TRIG, out)
              import org.eclipse.rdf4j.rio.RDFHandlerException

              try
                 wr.startRDF
                 import scala.jdk.CollectionConverters.{*, given}
                 for st <- model.iterator().asScala do wr.handleStatement(st)
                 wr.endRDF
              catch
                 case e: RDFHandlerException =>
              // todo oh no, do something!
              finally
                 out.close
              f(HttpResponse(
                StatusCodes.OK,
                Seq(),
                HttpEntity(`application/trig`.toContentType, new String(out.toByteArray))
              ))

           /* Jena version of how to write out TriG
  todo: abstract and move to banana-rdf

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
                   x          <- injectDataSetResponse(dataSetReq)
                yield x
              cmdmsg.continue(res)
           else cmdmsg.respondWith(plainGet(req))
           Behaviors.same

        override def plainGet(req: HttpRequest): HttpResponse =
           // todo: avoid duplication of mediaRange calculation -- requires change of signature of overriden method.
           val mediaRanges: Seq[MediaRange] = req.headers[Accept].flatMap(_.mediaRanges)
           if lastVersion == 0 then
              RdfParser.response(defaultGraph, mediaRanges)
                .withHeaders(Link(AclLink) :: ACResource.VersionHeaders)
           else super.plainGet(req)

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
              case _ => cmd.respondWith(HttpResponse(
                  StatusCodes.NotImplemented,
                  entity = HttpEntity("we don't yet deal with PUT for " + cmd.commands)
                ))
          else super.Put(cmd)
        end Put

        def defaultGraph: Rdf#Graph =
           import ops.Graph
           import run.cosy.http.util.UriX.*
           if dotLinkName.isContainerAcl then
              if uri.path.container == Uri.Path./ then
                 Graph.empty
              else Resource.defaultACLGraphContainer
           else
              Resource.defaultACLGraph

   end VersionsInfo
end ACResource
