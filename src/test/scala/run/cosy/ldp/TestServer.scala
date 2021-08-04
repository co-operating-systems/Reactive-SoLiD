package run.cosy.ldp

import akka.http.scaladsl.model.Uri
import org.w3.banana.PointedGraph
import run.cosy.RDF.*
import run.cosy.RDF.Prefix.*
import run.cosy.RDF.ops.*
import run.cosy.ldp.ImportsDLTestServer.{base, db}

trait TestServer:
	lazy val podRdfURI: Rdf#URI = base.toRdf

	/** the root URi of the server */
	def base: Uri

	def path(u: String): Uri = base.withPath(Uri.Path(u))

	def podGr(pg: PointedGraph[Rdf]): Rdf#Graph = pg.graph
	def absolutize(gr: Rdf#Graph): Rdf#Graph = gr.resolveAgainst(base.toRdf)

	/** the server as a simple map of graphs (we may want to extend this to content) */
	def db: Map[Uri, Rdf#Graph]
	
	/**
	 * DB where the graphs have absolute URLs.
	 * @return
	 */
	lazy val absDB: Map[Uri, Rdf#Graph] = db.map{
		case (uri,g) => uri -> g.resolveAgainst(uri.resolvedAgainst(base).toRdf)
	}


