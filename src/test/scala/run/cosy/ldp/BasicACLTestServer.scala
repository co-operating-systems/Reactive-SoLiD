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
object BasicACLTestServer extends TestServer:
	import cats.implicits.*
	import cats.{Applicative, CommutativeApplicative, Eval}

	val base = Uri("https://w3.org")

	val cardAcl = podGr(URI("/People/Berners-Lee/card.acl") -- owl.imports ->- URI("/People/Berners-Lee/.acl"))

	val BLAcl   = podGr( URI("/People/Berners-Lee/.acl#TimRl").a(wac.Authorization)
		-- wac.agent ->- URI("/People/Berners-Lee/card#i")
		-- wac.mode ->- wac.Control
		-- wac.default ->-  URI("/People/Berners-Lee/")
	) union podGr(
		URI("/People/Berners-Lee/.acl") -- owl.imports ->- URI("/.acl")
	)

	val pplAcl  = podGr(
		URI("/People/.acl#AdminRl").a(wac.Authorization)
			-- wac.mode ->- wac.Control
			-- wac.agent ->- URI("/People/HR#i")
			-- wac.default ->- URI("/People/")
	) union podGr(URI("/People/.acl") -- owl.imports ->- URI("/.acl"))

	val rootACL = podGr(URI("/.acl#Admin").a(wac.Authorization)
		-- wac.mode ->- wac.Control
		-- wac.agent ->- URI("/owner#i")
		-- wac.default ->- URI("/")
	) union podGr(
		URI("/.acl#Public").a(wac.Authorization)
			-- wac.accessTo ->- URI("/")
			-- wac.default ->- URI("/")
			-- wac.mode ->- wac.Read
			-- wac.agentClass ->- foaf.Agent
	)

	val rootIndexACL = podGr(URI("/index.acl") -- owl.imports ->- URI("/.acl"))

	val db: Map[Uri, Rdf#Graph] = Map(
		path("/.acl") -> rootACL,
		path("/index.acl") -> rootIndexACL,
		path("/People/.acl") -> pplAcl,
		path("/People/Berners-Lee/.acl") -> BLAcl,
		path("/People/Berners-Lee/card.acl") -> cardAcl
	)

