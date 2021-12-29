package run.cosy.ldp

import akka.http.scaladsl.model.*
import akka.stream.Materializer
import cats.{Applicative, Now}
import cats.free.{Cofree, Free}
import cats.free.Free.{Pure, liftF}
import alleycats.std.set.*
import cats.kernel.CommutativeMonoid
import org.apache.jena.sparql.core.NamedGraph
import run.cosy.RDF
import run.cosy.RDF.*
import run.cosy.RDF.ops.*
import run.cosy.ldp.rdf.LocatedGraphScriptExt.LocatedGraph
import run.cosy.ldp.rdf.LocatedGraphs

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}


/**
 * Commands on the Server. All commands are executed on a resource
 * named by a URL.
 */
sealed trait SolidCmd[A]:
	def url: Uri

object SolidCmd {
	// type NamedGraph = (Uri, Rdf#Graph)
	// type NamedGraphs = Map[Uri,Rdf#Graph]

	type Script[A] = cats.free.Free[SolidCmd, A]
	/**
	 * A recursively defined DataSet where the first layer points to a Cofree graph with no
	 * metadata and the included sets contain metadata.
	 *
	 * This differs from RDF1.1 DataSets in that
	 *  - DataSets can contain DataSets (which is [[https://lists.w3.org/Archives/Public/public-n3-dev/2021May/0012.html possible in N3]])
	 *  - the metadata is Http structured, whereas in RDF1.1 DS these are just URIs or bnodes
	 *  - this structure does not say anything about sharing of blank nodes
	 *
	 * Example
	 * GraF(ttl"<> a TeaPot.",
	 * Set(Cofree(Meta(Uri("/People/.acl"),
	 * GraF(ttl"<> a Kettle.", Set())))
	 */
	type RDataSet = GraF[Cofree[GraF, Meta]]
	/**
	 * MetaData on a Request for a DataSet
	 * eg Cofree(Meta(Uri("/.acl"),GraF(ttl"<> a TeaPot.", Set( ... other datasets ...))))
	 * Actually this is badly named. What RDF understands as a DataSet is
	 * closer to the type returned by tailForced namely the above defined
	 * type RDataSet = GraF[Cofree[GraF, Meta]]
	 */
	type ReqDataSet = Cofree[GraF, Meta]

	def pure[A](a: A): Script[A] = Free.pure(a)

	def get(key: Uri): Script[Response] = liftF[SolidCmd, Response](Get[Response](key, identity))

	/**
	 * Build a script that will start by fetching `url`, unmarshall the result into an `Rdf#Graph` inside a Response
	 * and apply the continuation `k` to that Response.
	 *
	 * @param url to GET
	 * @param k   the contination to use once the Response is available
	 * @tparam A
	 * @return The resulting script
	 */
	def getFromPlain[A](
		url: Uri, k: Response => Script[A]
	)(using ExecutionContext, Materializer): Script[A] =
		import run.cosy.http.{IResponse, RdfParser}

		import scala.util.Failure
		for {
			resp <- SolidCmd.plainScript(RdfParser.rdfRequest(url))
			tryResp <- resp.status match
				case good: StatusCodes.Success =>
					val fg: Future[IResponse[Rdf#Graph]] = RdfParser.unmarshalToRDF(resp, url)
					//todo: clearly these are close to isomorphic, so we don't need both structures
					val fgres: Future[Response] = fg.map { ir =>
						Response(Meta(ir.origin, ir.status, ir.headers), Success(ir.content))
					}
					SolidCmd.wait[Response](fgres, url)
				case other =>
					SolidCmd.wait[Response](Future.failed(
						new Throwable("could not parse graph. todo: better error message")), url)
			x <- k(tryResp.getOrElse(
				Response(Meta(url, StatusCodes.InternalServerError), Failure(new Throwable("todo")))
			))
		} yield x

	def plainScript(req: HttpRequest): Script[HttpResponse] = liftF[SolidCmd, HttpResponse](Plain(req, identity))
	
	//todo: The Wait does not have a URL to send it to (or does it always send it to the same actor?)
	def wait[A](ftr: Future[A], target: Uri): Script[Try[A]] =
		liftF[SolidCmd, Try[A]](Wait(ftr, target, identity))

	def plain(req: HttpRequest): SolidCmd[Script[HttpResponse]] = Plain(req, Free.pure[SolidCmd, HttpResponse])
	
	/**
	 * Build a Script to fetch owl:imports links starting from a graph at u, avoiding visted ones.
	 * See [[https://github.com/solid/authorization-panel/issues/210 Issue 210 on :imports]], though here
	 * we use owl.imports provisionally until a better name is chosen.
	 *
	 * The script returns an ReqDataSet as defined here.
	 * It is quite possible that different branches could fetch the same graphs, but it should not lead to infinite loops.
	 * todo: there is parallelism here when fetching children: would it work to use Applicatives?
	 * todo: returning a Set may be better, but I had trouble getting Traverse to work on Set
	 */
	def fetchWithImports(u: Uri, visited: Set[Uri] = Set()): Script[ReqDataSet] = for {
		response <- get(u)
		ngs: ReqDataSet <- response match
			case Response(Meta(url, StatusCodes.OK, headers), Success(graph)) =>
				import cats.syntax.all.toTraverseOps
				val imports: Set[Uri] = find(graph, ANY, owl.imports, ANY).toSet.collect {
					case Triple(_, _, o: Rdf#URI) => o.toAkka
				}
				val newVisited = visited + u
				val newImports: Set[Uri] = imports.filterNot(newVisited.contains(_))
				val covered = newVisited ++ newImports
				val scrptDs: Set[Script[ReqDataSet]] = newImports.map(u => fetchWithImports(u, covered))
				val x: Script[Set[ReqDataSet]] = scrptDs.sequence
				x.map(subs => Cofree(Meta(u), Now(GraF(graph, subs))))
			case Response(meta, _) => //any other result is problematic for the moment.
				//todo: pass more detailed error info into the result below - needed to explain problems
				cats.free.Free.pure[SolidCmd, ReqDataSet](Cofree(meta, Now(GraF(Graph.empty,Set()))))
	} yield ngs

	/**
	 * take the union of all the graphs in this set. ie: take all graphs to be true, including internal ones.
	 * @param ds
	 * @return
	 */
	def unionAll(ds: ReqDataSet): LocatedGraphs.LGs =
		Cofree.cata[GraF,Meta,Rdf#Graph](ds)((_, d) => cats.Now(union(d.other.toSeq :+ d.graph))).value

	/**
	 * Get request from URL, but the response should be interpreted to a
	 * manipulatable Scala object. Eg. an RDF Graph for an RDF resource.
	 *
	 * We don't start with a generalised version, to keep things simple.
	 *
	 * ideas: one may want to pass
	 *  - etag, date-since-previous-version,
	 *  - authenticated agent info,
	 *  - content-type?
	 *  - ...
	 *    It captures a request that reutrns a Response, where
	 *    k is a function to transform that response into any A
	 * */
	case class Get[A](url: Uri, k: Response => A) extends SolidCmd[A]

	/**
	 * This is the base class of commands that covers all the Http Requests.
	 * This is essentially done in terms of
	 * Execution of such a Request will return an HttpResponse
	 *
	 * @param k the continuation function
	 * */
	case class Plain[A](req: HttpRequest, k: HttpResponse => A) extends SolidCmd[A] :
		def url: Uri = req.uri

	/** Wait that a future has a result. In terms of actors this can use the pipeToSelf command */
	case class Wait[A, B](ftr: Future[A], url: Uri, k: Try[A] => B) extends SolidCmd[B]

	/**
	 * some Metadata on the resource from the server.
	 *
	 * @param the     response on the message - whether the request failed or not
	 * @param url     the URL of the resource
	 * @param headers : Seq of Http Headers (this could also be represented as a Graph)
	 */
	case class Meta(url: Uri, code: StatusCode = StatusCodes.OK, headers: Seq[HttpHeader] = Seq())

	/**
	 * A response to a request.
	 * The Metadata tells if the response succeeded, what the problems may have been, etc...
	 * The content, is an attempted parse of the stream to the object type.
	 * (todo?: generalise the type of the content to quite a lot more types, such
	 * as DataSets, Images, streams, ...)
	 * */
	case class Response(meta: Meta, content: Try[Rdf#Graph])

	/*
	 * CoFree as as defined in [[http://tpolecat.github.io/presentations/cofree/slides#19 tpolecat's Fixpoint slides]].
	 *
	 * cats Cofree wraps the tail in an Eval, which may not be that useful for use cases here, Except
	 * perhaps that if the data structure gets somewhat large then duplicate may be quite expensive...
	 **/
	case class CoFree[F[_], A](head: A, tail: F[CoFree[F, A]])

	/*
	 * Graph Functor -- modelled on tpolecat's ProfF example
	 * Todo: look into what other functor would be more interesting Functor for other:
	 * a HashMap, a Graph?
	 * We use a Set as the order in which we receive the other children is indeterminate,
	 * and is often related to traversing the graph (which itself is a set like structure).
	 **/
	case class GraF[A](graph: Rdf#Graph, other: Set[A] = Set())

	import cats.{Applicative, CommutativeApplicative, Eval, Traverse}
	import cats.implicits.*

	given CmdFunctor: cats.Functor[SolidCmd] with
		def map[A, B](fa: SolidCmd[A])(f: A => B): SolidCmd[B] = fa match
			//todo: k andThen can lead to stackoverflow. Look at cats.Coyoneda which uses Function.chain
			case Get(url, k) => Get(url, k andThen f)
			case Plain(req, k) => Plain(req, k andThen f)
			case Wait(ftr, url, k) => Wait(ftr, url, k andThen f)
	

	given GraFTraverse: cats.Traverse[GraF] with
		override
		def traverse[G[_] : Applicative, A, B](fa: GraF[A])(f: A => G[B]): G[GraF[B]] =
			fa.other.traverse(f).map(ss => fa.copy(other = ss))

		override
		def foldLeft[A, B](fa: GraF[A], b: B)(f: (B, A) => B): B = fa.other.foldLeft(b)(f)

		override
		def foldRight[A, B](fa: GraF[A], lb: cats.Eval[B])(f: (A, cats.Eval[B]) => cats.Eval[B]): cats.Eval[B] = ???


}




