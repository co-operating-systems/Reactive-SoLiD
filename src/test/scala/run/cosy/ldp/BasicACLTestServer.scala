package run.cosy.ldp

import akka.http.scaladsl.model.Uri
import run.cosy.RDF.Prefix.{foaf, wac}

import run.cosy.RDF.*
import run.cosy.RDF.Prefix.*
import run.cosy.RDF.ops.*

/**
 * A basic ACL server that can be used to test Free Monad Commands on.
 * The setup is close to the one illustrated
 * [[https://github.com/solid/authorization-panel/issues/210#issuecomment-838747077 in the diagram of issue210]] and
 * implemented in ImportsTestServer except that we only use `wac:default` and `:imports`
 * but no more complex OWL rules
 **/
case class BasicACLTestServer(base: Uri) extends TestServer:
	import cats.implicits.*
	import cats.{Applicative, CommutativeApplicative, Eval}
	type LocGraph = Tuple2[Uri, Rdf#Graph]

	val cardAcl = path("/People/Berners-Lee/card.acl") -> podGr(
		URI("") -- owl.imports ->- URI("../.acl")
	)

	val BLAcl   = path("/People/Berners-Lee/.acl") -> {
		podGr(
			URI("#TimCtrl").a(wac.Authorization)
				-- wac.agent ->- URI("card#i")
				-- wac.mode ->- (wac.Read, wac.Write)
				-- wac.accessTo ->- URI("")
		) union podGr(
			URI("#TimRule").a(wac.Authorization)
				-- wac.agent ->- URI("card#i")
				-- wac.mode ->- (wac.Read, wac.Write)
				-- wac.default ->- URI(".")
		) union podGr(
			URI(".") -- owl.imports ->- URI("/.acl")
		)
	}

	val pplAcl  = path("/People/.acl") -> {
		podGr(
			URI("#AdminCtrl").a(wac.Authorization)
				-- wac.mode ->- (wac.Read, wac.Write)
				-- wac.agent ->- URI("HR#i")
				-- wac.accessTo ->- URI("")
		) union podGr(
			URI("#AdminRl").a(wac.Authorization)
				-- wac.mode ->- (wac.Read, wac.Write)
				-- wac.agent ->- URI("HR#i")
				-- wac.default ->- URI(".")
		) union podGr(URI("") -- owl.imports ->- URI("/.acl"))
	}

	val rootACL: LocGraph = path("/.acl") -> {
		podGr(
			URI("#AdminCtrl").a(wac.Authorization)
				-- wac.mode ->- (wac.Read, wac.Write)
				-- wac.agent ->- URI("/owner#i")
				-- wac.accessTo ->- URI("") //todo: using "" here throws an exception with Rdf4j
		) union podGr( 
			URI("#Admin").a(wac.Authorization)
				-- wac.mode ->- (wac.Read, wac.Write)
				-- wac.agent ->- URI("/owner#i")
				-- wac.default ->- URI("/")
		) union podGr(
			URI("#Public").a(wac.Authorization)
				-- wac.default ->- URI("/")
				-- wac.mode ->- wac.Read
				-- wac.agentClass ->- foaf.Agent
		)
	}

	val rootIndexACL: LocGraph = path("/index.acl") -> podGr(
		URI("") -- owl.imports ->- URI("/.acl")
	)

	val db: Map[Uri, Rdf#Graph] = Map(
		rootACL, rootIndexACL, pplAcl, BLAcl, cardAcl
	)
end BasicACLTestServer

object BasicACLTestServerW3C extends BasicACLTestServer(Uri("https://w3.org"))

