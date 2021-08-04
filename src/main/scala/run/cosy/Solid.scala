package run.cosy

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, LoggerOps}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, PostStop, Scheduler}
import akka.http.scaladsl.settings.ParserSettings
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.Uri.Path.{Empty, Segment, Slash}
import akka.http.scaladsl.model
import akka.http.scaladsl.model.headers
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.server.Directives.{complete, extract, extractRequestContext}
import akka.http.scaladsl.server.{RequestContext, Route, RouteResult}
import akka.http.scaladsl.settings.ServerSettings
import akka.http.scaladsl.util.FastFuture
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import org.w3.banana.PointedGraph
import run.cosy.http.{IResponse, RDFMediaTypes, RdfParser}
import run.cosy.http.util.UriX.*
import run.cosy.http.auth.{Agent, Anonymous, SignatureVerifier, KeyIdAgent, WebServerAgent}
import run.cosy.ldp.ResourceRegistry
import run.cosy.ldp.{Messages => LDP}
import scalaz.NonEmptyList
import scalaz.NonEmptyList.nel

import java.io.{File, FileInputStream}
import java.nio.file.{Files, Path}
import javax.naming.AuthenticationException
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn
import scala.util.{Failure, Success}


object Solid {
	//todo: make @tailrec
	def pathToList(path: Uri.Path): List[String] = path match {
		case Empty => Nil
		case Segment(head, tail) => head :: pathToList(tail)
		case Slash(tail) => pathToList(tail) //note: ignore slashes. But could they be useful?
	}

	def apply(uri: Uri, fpath: Path): Behavior[Run] =
		Behaviors.setup { (ctx: ActorContext[Run]) =>
			import run.cosy.ldp.fs.BasicContainer
			given system: ActorSystem[Nothing] = ctx.system
			given reg : ResourceRegistry = ResourceRegistry(ctx.system)
			val rootRef: ActorRef[LDP.Cmd] = ctx.spawn(BasicContainer(uri.withoutSlash, fpath), "solid")
			val registry = ResourceRegistry(system)
			val solid = new Solid(uri, fpath, registry, rootRef)
			given timeout: Scheduler = system.scheduler
			given ec: ExecutionContext = ctx.executionContext

			val ps = ParserSettings.forServer(system).withCustomMediaTypes(RDFMediaTypes.all :_*)
			val ss1 = ServerSettings(system)
			val serverSettings = ss1.withParserSettings(ps)
				.withServerHeader(Some(headers.Server(
						headers.ProductVersion("reactive-solid","0.3"),
						ss1.serverHeader.toSeq.flatMap(_.products)*)))
				.withDefaultHostHeader(headers.Host(uri.authority.host,uri.authority.port))

			val serverBinding = Http()
				.newServerAt(uri.authority.host.address(), uri.authority.port)
				.withSettings(serverSettings)
				.bind(solid.routeLdp())

			ctx.pipeToSelf(serverBinding) {
				case Success(binding) =>
					val shutdown = CoordinatedShutdown(system)

					shutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, "http-unbind") { () =>
						binding.unbind().map(_ => Done)
					}
					import concurrent.duration.DurationInt
					shutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone, "http-graceful-terminate") { () =>
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
							binding.localAddress.getPort)
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
						ctx.log.info("Server online at http://{}:{}/",
							binding.localAddress.getHostString,
							binding.localAddress.getPort)
						if (wasStopped) ctx.self ! Stop
						running(binding)
					case Stop =>
						// we got a stop message but haven't completed starting yet,
						// we cannot stop until starting has completed
						starting(wasStopped = true)
				}

			starting(wasStopped = false)
		}

	//question: could one use ADTs instead?
	sealed trait Run
	case class StartFailed(cause: Throwable) extends Run
	case class Started(binding: ServerBinding) extends Run
	case object Stop extends Run

}

/**
 * The object from from which the solid server is called.
 * This object also keeps track of actorRef -> path mappings
 * so that the requests can go directly to the right actor for a resource
 * once all the intermediate containers have been set up.
 *
 * We want to have intermediate containers so that some can be setup
 * to read from the file system, others from git or CVS, others yet from
 * a DB, ... A container actor would know what behavior it implements by
 * looking at some config file in that directory.
 *
 * This is an object that can be called simultaneously by any number
 * of threads, so all state in it should be protected by Atomic locks.
 *
 * @param path    to the root directory of the file system to be served
 * @param baseUri of the container, eg: https://alice.example/solid/
 */
class Solid(
	baseUri: Uri,
	path: Path,
	registry: ResourceRegistry,
	rootRef: ActorRef[LDP.Cmd]
)(using sys: ActorSystem[_]) {

	import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
	import run.cosy.http.auth.HttpSigDirective
	import akka.pattern.ask
	import run.cosy.http.headers.akka.given

	import scala.concurrent.duration.*
	import scala.jdk.CollectionConverters.*
	given timeout: Scheduler = sys.scheduler
	given scheduler: Timeout = Timeout(5.second)
	given reg: ResourceRegistry = registry

	def fetchKeyId(keyIdUrl: Uri)(reqc: RequestContext): Future[SignatureVerifier[KeyIdAgent]] = {
		import RouteResult.{Complete,Rejected}
		import run.cosy.RDF.{given,_}
		import run.cosy.RDF.ops.{given,_}

		given ec: ExecutionContext = reqc.executionContext
		val req = RdfParser.rdfRequest(keyIdUrl)
		if keyIdUrl.isRelative then  //we get the resource locally
			routeLdp(WebServerAgent)(reqc.withRequest(req)).flatMap{
				case Complete(response) => RdfParser.unmarshalToRDF(response,keyIdUrl).flatMap{ (g: IResponse[Rdf#Graph]) =>
					import http.auth.JWKExtractor.*, http.auth.JW2JCA.jw2rca
					PointedGraph(keyIdUrl.toRdfNode,g.content).asKeyIdInfo match
						case Some(kidInfo) => FastFuture(jw2rca(kidInfo.pka,keyIdUrl))
						case None => FastFuture.failed(http.AuthException(null, //todo
							s"Could not find or parse security:publicKeyJwk relation in <$keyIdUrl>"
						 ))
				}
				case r: Rejected => FastFuture(Failure(new Throwable(r.toString))) //todo
			}
		else // we get it from the web
			???
	}

	lazy val securedRoute: Route = extractRequestContext { (reqc: RequestContext) =>
		HttpSigDirective.httpSignature(reqc)(fetchKeyId(_)(reqc)).optional.tapply {
			case Tuple1(Some(agent)) => routeLdp(agent)
			case Tuple1(None) => routeLdp()	
		}
	}
	import run.cosy.ldp.SolidCmd

	def routeLdp(agent: Agent = new Anonymous()): Route = (reqc: RequestContext) => {
		val path = reqc.request.uri.path
		import reqc.{given}
		reqc.log.info("routing req " + reqc.request.uri)
		val (remaining, actor): (List[String], ActorRef[LDP.Cmd]) = registry.getActorRef(path)
			.getOrElse((List[String](), rootRef))
		reqc.log.info(s"($remaining, $actor) = registry.getActorRef($path)")

		def routeWith(replyTo: ActorRef[HttpResponse]): LDP.Cmd = LDP.RouteMsg(
				NonEmptyList.fromSeq("/",remaining.toSeq),
				LDP.CmdMessage(SolidCmd.plain(reqc.request), agent, replyTo)
			).nextRoute

		actor.ask[HttpResponse](routeWith).map(RouteResult.Complete(_))
	}


	//  def handle(request: HttpRequest): Future[HttpResponse] = {
	//    // todo: what does the type of this message have to be to receive a HttpResponse?
	//      registry.getActorRef(request.uri.path) match {
	//        case Some(ref) => ctxt.ask(ref) toMessage(request).asInstanceOf[Future[HttpResponse]]
	//        case None => Future.successful(HttpResponse(StatusCodes.ServiceUnavailable))  
	//      }


}

