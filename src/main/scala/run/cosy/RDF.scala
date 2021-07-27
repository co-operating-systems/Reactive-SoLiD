package run.cosy

import org.w3.banana.jena.Jena
import akka.http.scaladsl.model.Uri
import org.apache.jena.sparql.vocabulary.FOAF
import org.w3.banana.FOAFPrefix
/**
 * Set your preferred implementation of banana-rdf here.
 * Note: this way of setting a preferred implementation of RDF means that
 * all code referring to this must use one implementation of banana at compile time.
 * A more flexible but more tedious  approach, where different parts of the code
 * could use different RDF implementations, and interact via translations, would require all the
 * code to take Rdf and ops implicit parameters `given` with the `using` keyword.
 *
 **/
object RDF {
	export org.w3.banana.rdf4j.Rdf4j._
	export org.w3.banana.rdf4j.Rdf4j.given

	extension (uri: Uri)
		def toRdf: Rdf#URI = ops.URI(uri.toString)
		//inheritance of type projections does not work in Scala 3
		def toRdfNode: Rdf#Node = ops.URI(uri.toString)

	extension (rdfUri: Rdf#URI)
		def toAkka: Uri = Uri(ops.getString(rdfUri))
		
	import org.w3.banana.binder.ToNode
	import org.w3.banana.binder.ToNode.{given,_}

	// todo: add this to banana
	//see https://github.com/lampepfl/dotty/discussions/12527
	implicit val URIToNode: ToNode[Rdf,Rdf#URI] = new ToNode[Rdf, Rdf#URI]:
		def toNode(t: Rdf#URI): Rdf#Node = t

	implicit val BNodeToNode: ToNode[Rdf,Rdf#BNode] = new ToNode[Rdf, Rdf#BNode]:
		def toNode(t: Rdf#BNode): Rdf#Node = t

	implicit val LiteralToNode: ToNode[Rdf,Rdf#Literal] = new ToNode[Rdf, Rdf#Literal]:
		def toNode(t: Rdf#Literal): Rdf#Node = t


//	implicit val URIToNodeConv: Conversion[Rdf#URI, Rdf#Node] = (u: Rdf#URI) =>  u.asInstanceOf[Rdf#Node]
//	implicit val LiteralToNodeConv: Conversion[Rdf#Literal, Rdf#Node] = (lit: Rdf#Literal) =>  lit.asInstanceOf[Rdf#Node]
//	implicit val BNodeToNodeConv: Conversion[Rdf#BNode, Rdf#Node] = (bn: Rdf#BNode) =>  bn.asInstanceOf[Rdf#Node]

	object Prefix {

		import org.w3.banana.{LDPPrefix, RDFPrefix, WebACLPrefix}
		import run.cosy.http.auth.SecurityPrefix

		val wac = WebACLPrefix[Rdf]
		val rdf = RDFPrefix[Rdf]
		val ldp = LDPPrefix[Rdf]
		val foaf = FOAFPrefix[Rdf]
		val security = SecurityPrefix[Rdf]

	}

}

