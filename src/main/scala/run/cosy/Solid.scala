/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy

import _root_.akka.Done
import _root_.akka.actor.CoordinatedShutdown
import _root_.akka.actor.typed.scaladsl.{ActorContext, Behaviors, LoggerOps}
import _root_.akka.actor.typed.{ActorRef, ActorSystem, Behavior, PostStop, Scheduler}
import _root_.akka.http.scaladsl.settings.ParserSettings
import _root_.akka.http.scaladsl.Http
import _root_.akka.http.scaladsl.Http.ServerBinding
import _root_.akka.http.scaladsl.model.Uri.Path.{Empty, Segment, Slash}
import _root_.akka.http.scaladsl.model
import _root_.akka.http.scaladsl.model.headers
import _root_.akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, Uri}
import _root_.akka.http.scaladsl.server.Directives
import _root_.akka.http.scaladsl.server.Directives.{complete, extract, extractRequestContext}
import _root_.akka.http.scaladsl.server.{RequestContext, Route, RouteResult}
import _root_.akka.http.scaladsl.settings.ServerSettings
import _root_.akka.http.scaladsl.util.FastFuture
import _root_.akka.util.Timeout
import _root_.com.typesafe.config.{Config, ConfigFactory}
import _root_.org.w3.banana.PointedGraph
import _root_.run.cosy.http.{IResponse, RDFMediaTypes, RdfParser}
import _root_.run.cosy.http.util.UriX.*
import _root_.run.cosy.http.auth.{
  Agent,
  Anonymous,
  KeyIdAgent,
  MessageSignature,
  SignatureVerifier,
  WebServerAgent
}
import _root_.run.cosy.ldp.ResourceRegistry
import _root_.run.cosy.ldp.Messages as LDP
import _root_.scalaz.NonEmptyList
import _root_.scalaz.NonEmptyList.nel
import cats.effect.IO
import run.cosy.http.headers.SelectorOps
import run.cosy.http.Http as cHttp
import _root_.java.io.{File, FileInputStream}
import _root_.java.nio.file.{Files, Path}
import _root_.javax.naming.AuthenticationException
import _root_.scala.annotation.tailrec
import _root_.scala.concurrent.{ExecutionContext, Future}
import _root_.scala.io.StdIn
import _root_.scala.util.{Failure, Success}

object Solid:
   // todo: make @tailrec
   def pathToList(path: Uri.Path): List[String] = path match
    case Empty               => Nil
    case Segment(head, tail) => head :: pathToList(tail)
    case Slash(tail)         => pathToList(tail) // note: ignore slashes. But could they be useful?

   def apply(uri: Uri, fpath: Path): Behavior[Run] =
     Behaviors.setup { (ctx: ActorContext[Run]) =>
        import run.cosy.ldp.fs.BasicContainer
        given system: ActorSystem[Nothing] = ctx.system
        given reg: ResourceRegistry        = ResourceRegistry(ctx.system)
        val rootRef: ActorRef[LDP.Cmd] = ctx.spawn(BasicContainer(uri.withoutSlash, fpath), "solid")
        val registry                   = ResourceRegistry(system)
        val solid                      = new Solid(uri, fpath, registry, rootRef)
        given timeout: Scheduler       = system.scheduler
        given ec: ExecutionContext     = ctx.executionContext

        val ps  = ParserSettings.forServer(system).withCustomMediaTypes(RDFMediaTypes.all*)
        val ss1 = ServerSettings(system)
        val serverSettings = ss1.withParserSettings(ps)
          .withServerHeader(
            Some(headers.Server(
              headers.ProductVersion("reactive-solid", "0.3"),
              ss1.serverHeader.toSeq.flatMap(_.products)*
            ))
          )
          .withDefaultHostHeader(headers.Host(uri.authority.host, uri.authority.port))

        val serverBinding = Http()
          .newServerAt(uri.authority.host.address(), uri.authority.port)
          .withSettings(serverSettings)
          .bind(solid.securedRoute)

        ctx.pipeToSelf(serverBinding) {
          case Success(binding) =>
            val shutdown = CoordinatedShutdown(system)

            shutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, "http-unbind") { () =>
              binding.unbind().map(_ => Done)
            }
            import concurrent.duration.DurationInt
            shutdown.addTask(
              CoordinatedShutdown.PhaseServiceRequestsDone,
              "http-graceful-terminate"
            ) { () =>
              binding.terminate(10.seconds).map(_ => Done)
            }
            shutdown.addTask(CoordinatedShutdown.PhaseServiceStop, "http-shutdown") { () =>
              Http().shutdownAllConnectionPools().map(_ => Done)
            }
            Started(binding)
          case Failure(ex) => StartFailed(ex)
        }

        def running(binding: ServerBinding): Behavior[Run] =
          Behaviors.receiveMessagePartial[Run] {
            case Stop =>
              ctx.log.info(
                "Stopping server http://{}:{}/",
                binding.localAddress.getHostString,
                binding.localAddress.getPort
              )
              Behaviors.stopped
          }.receiveSignal {
            case (_, PostStop) =>
              binding.unbind()
              Behaviors.same
          }

        def starting(wasStopped: Boolean): Behaviors.Receive[Run] =
          Behaviors.receiveMessage[Run] {
            case StartFailed(cause) =>
              throw new RuntimeException("Server failed to start", cause)
            case Started(binding) =>
              ctx.log.info(
                "Server online at http://{}:{}/",
                binding.localAddress.getHostString,
                binding.localAddress.getPort
              )
              if wasStopped then ctx.self ! Stop
              running(binding)
            case Stop =>
              // we got a stop message but haven't completed starting yet,
              // we cannot stop until starting has completed
              starting(wasStopped = true)
          }

        starting(wasStopped = false)
     }

   // question: could one use ADTs instead?
   sealed trait Run
   case class StartFailed(cause: Throwable)   extends Run
   case class Started(binding: ServerBinding) extends Run
   case object Stop                           extends Run

/** The object from from which the solid server is called. This object also keeps track of actorRef
  * -> path mappings so that the requests can go directly to the right actor for a resource once all
  * the intermediate containers have been set up.
  *
  * We want to have intermediate containers so that some can be setup to read from the file system,
  * others from git or CVS, others yet from a DB, ... A container actor would know what behavior it
  * implements by looking at some config file in that directory.
  *
  * This is an object that can be called simultaneously by any number of threads, so all state in it
  * should be protected by Atomic locks.
  *
  * @param path
  *   to the root directory of the file system to be served
  * @param baseUri
  *   of the container, eg: https://alice.example/solid/
  */
class Solid(
    baseUri: Uri,
    path: Path,
    registry: ResourceRegistry,
    rootRef: ActorRef[LDP.Cmd]
)(using sys: ActorSystem[?]):

   import _root_.akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
   import _root_.run.cosy.http.auth.HttpSigDirective
   import _root_.run.cosy.akka.http.headers.AkkaMessageSelectors
   import _root_.akka.pattern.ask
   import _root_.run.cosy.akka.http.headers.given

   import _root_.scala.concurrent.duration.*
   import _root_.scala.jdk.CollectionConverters.*

   given timeout: Scheduler    = sys.scheduler
   given scheduler: Timeout    = Timeout(5.second)
   given reg: ResourceRegistry = registry

   // because we use cats.effect.IO we need a
   // but we could also do without it by just using Future
   // see https://github.com/typelevel/cats-effect/discussions/1562#discussioncomment-2249643
   // todo: perhaps move to using Future
   import cats.effect.unsafe.implicits.global
   import run.cosy.akka.http.AkkaTp.HT as H4
   given selectorOps: SelectorOps[cHttp.Request[IO,H4]] = new AkkaMessageSelectors[IO](
     true,
     baseUri.authority.host,
     baseUri.effectivePort
   ).requestSelectorOps
   
   def isLocalUrl(url: Uri): Boolean =
     url.scheme == baseUri.scheme  
       && url.authority.host == baseUri.authority.host 
       && url.authority.port == baseUri.authority.port

   def fetchKeyId(keyIdUrl: Uri)(reqc: RequestContext)
       : IO[MessageSignature.SignatureVerifier[IO, KeyIdAgent]] =
      import RouteResult.{Complete, Rejected}
      import run.cosy.RDF.{given, *}
      import run.cosy.RDF.ops.{given, *}

      given ec: ExecutionContext = reqc.executionContext
      val req                    = RdfParser.rdfRequest(keyIdUrl)
      // todo: also check if the resource is absolute but we can determine it is on the current server
      if keyIdUrl.isRelative || isLocalUrl(keyIdUrl) then // we get the resource locally
         IO.fromFuture(IO(routeLdp(WebServerAgent)(reqc.withRequest(req)))).flatMap {
           case Complete(response) =>
             IO.fromFuture(IO(RdfParser.unmarshalToRDF(response, keyIdUrl)))
               .flatMap { (g: IResponse[Rdf#Graph]) =>
                  import http.auth.JWKExtractor.*, http.auth.JW2JCA.jw2rca
                  PointedGraph(keyIdUrl.toRdfNode, g.content).asKeyIdInfo match
                   case Some(kidInfo) => IO.fromTry(jw2rca(kidInfo.pka, keyIdUrl))
                   case None => IO.fromTry(Failure(http.AuthException(
                       null, // todo
                       s"Could not find or parse security:publicKeyJwk relation in <$keyIdUrl>"
                     )))
               }
           case r: Rejected => IO.fromTry(Failure(new Throwable(r.toString))) // todo
         }
      else // we get it from the web
        println("we have to get "+keyIdUrl+"from the web!!")
        ???
         
   lazy val securedRoute: Route = extractRequestContext { (reqc: RequestContext) =>
     HttpSigDirective.httpSignature(reqc)(fetchKeyId(_)(reqc)).optional.tapply {
       case Tuple1(Some(agent)) => routeLdp(agent)
       case Tuple1(None)        => routeLdp()
     }
   }

   import _root_.run.cosy.ldp.SolidCmd

   def routeLdp(agent: Agent = new Anonymous()): Route = (reqc: RequestContext) =>
      val path = reqc.request.uri.path
      import reqc.given
      reqc.log.info("routing req " + reqc.request.uri)
      val (remaining, actor): (List[String], ActorRef[LDP.Cmd]) = registry.getActorRef(path)
        .getOrElse((List[String](), rootRef))
      reqc.log.info(s"($remaining, $actor) = registry.getActorRef($path)")

      def routeWith(replyTo: ActorRef[HttpResponse]): LDP.Cmd = LDP.RouteMsg(
        NonEmptyList.fromSeq("/", remaining.toSeq),
        LDP.CmdMessage(SolidCmd.plain(reqc.request), agent, replyTo)
      ).nextRoute

      actor.ask[HttpResponse](routeWith).map(RouteResult.Complete(_))

   //  def handle(request: HttpRequest): Future[HttpResponse] = {
   //    // todo: what does the type of this message have to be to receive a HttpResponse?
   //      registry.getActorRef(request.uri.path) match {
   //        case Some(ref) => ctxt.ask(ref) toMessage(request).asInstanceOf[Future[HttpResponse]]
   //        case None => Future.successful(HttpResponse(StatusCodes.ServiceUnavailable))
   //      }
