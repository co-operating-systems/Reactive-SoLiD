package run.cosy.ldp

import akka.http.scaladsl.model.*
import cats.free.Cofree
import org.apache.jena.riot.lang.RiotParsers
import run.cosy.RDF
import run.cosy.RDF.Prefix.wac
import run.cosy.ldp.BasicACLTestServerW3C.base
import run.cosy.ldp.ConnectedImportsDLTestServer.base
import run.cosy.ldp.ImportsDLTestServer.{BLAcl, db}
import run.cosy.ldp.SolidCmd.*

import java.util.concurrent.TimeUnit
import scala.collection.immutable.HashMap
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}
// import org.w3.banana.{PointedGraph=>PG}
import org.w3.banana.*
import run.cosy.RDF.*
import run.cosy.RDF.Prefix.*
import run.cosy.RDF.ops.*

/**
 * A basic server that can be used to test Free Monad Commands on.
 * The setup is illustrated in the diagram
 * https://github.com/solid/authorization-panel/issues/210#issuecomment-838747077
 * */
object ImportsDLTestServer extends TestServer:
	import cats.implicits.*
	import cats.{Applicative, CommutativeApplicative, Eval}

	val base = Uri("https://w3.org")

	//todo: move out to test ontology, so it can be used by test code.
	val containedIn = URI("http://ont.example/containedIn")

	// let's put together the example illustrated in
	// https://github.com/solid/authorization-panel/issues/210#issuecomment-838747077
	val cardAcl = podGr(URI("/People/Berners-Lee/card.acl") -- owl
		.imports ->- URI("/People/Berners-Lee/.acl"))

	val BLAcl   = podGr(URI("/People/Berners-Lee/.acl#TimRl").a(wac.Authorization)
		-- wac.agent ->- (URI("/People/Berners-Lee/card#i")
		-- wac.accessToClass ->- (
			bnode() -- containedIn ->- URI("/People/Berners-Lee/")))
	) union podGr(
		URI("/People/Berners-Lee/.acl") -- owl.imports ->- URI("/.acl")
	)

	val pplAcl  = podGr(
		URI("/People/.acl#AdminRl").a(wac.Authorization)
			-- wac.mode ->- wac.Control
			-- wac.agent ->- URI("/People/HR#i")
			-- wac.accessToClass ->- (bnode() -- containedIn ->- URI("/People/"))
	) union podGr(URI("/People/.acl") -- owl.imports ->- URI("/.acl"))

	val rootACL = podGr(URI("/.acl#Admin").a(wac.Authorization)
		-- wac.mode ->- wac.Control
		-- wac.agentClass ->- URI("/Admins#g")
		-- wac.accessToClass ->- bnode("allContents")
	) union podGr(
		URI("/.acl#Public").a(wac.Authorization)
			-- wac.accessTo ->- URI("/")
			-- wac.mode ->- wac.Read
			-- wac.agentClass ->- foaf.Agent
	)

	val db: Map[Uri, Rdf#Graph] = Map(
		path("/.acl") -> rootACL,
		path("/People/.acl") -> pplAcl,
		path("/People/Berners-Lee/.acl") -> BLAcl,
		path("/People/Berners-Lee/card.acl") -> cardAcl
	)
	

end ImportsDLTestServer

/** A test server where every AC Resource points to its parent ACR */
object ConnectedImportsDLTestServer extends TestServer:
	val base = Uri("https://w3.org")

	// we also want to consider the world where the full import hierarchy is preserved
	val BLAcl2  = ImportsDLTestServer.BLAcl union podGr(
		URI("/People/Berners-Lee/.acl") -- owl.imports ->- URI("/People/.acl")
	)

	val db = ImportsDLTestServer.db + (path("/People/Berners-Lee/.acl") -> BLAcl2)
	
end ConnectedImportsDLTestServer