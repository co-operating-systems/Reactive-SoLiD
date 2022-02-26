/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp.rdf

import org.apache.commons.logging.Log
import run.cosy.RDF.*
import run.cosy.ldp.SolidCmd
import run.cosy.ldp.rdf.LocatedGraphs.LocatedGraph

import scala.util.Success

object LocatedGraphs:
   import ops.{given, *}
   type LocatedGraph = Located & WithGraph
   type PtsLGraph    = Pointed[LocatedGraph]

   trait WithGraph:
      def graph: Rdf#Graph

   /** Located (data/graphs) is data that has some location: ie found at one URL or a one of among a
     * set of know URLs.
     * I.e. we abstract NG and NGs
     */
   trait Located:
      /** Check if the given Uri is local. It is, if the node is a hash uri. Note: this does not
        * work for 303 redirects, which as one can see from this signature, are much more
        * complicated, as they would need to have the history of the request available, not just the
        * name of the node.
        */
      def isLocal(uri: Rdf#URI): Boolean

      /** As soon as we have a Located we can ask if a node is local or remote If `node` is local
        * then return `result` else apply remote.
        */
      def ifLocal[T](node: Rdf#Node)(result: => T)(remote: Rdf#URI => T): T =
        node.fold(u => if isLocal(u) then result else remote(u), _ => result, _ => result)

   /** A Located Graph
     * @param from
     *   the location of the Graph
     * @param graph
     */
   case class LG(from: Rdf#URI, graph: Rdf#Graph) extends WithGraph, Located:
      override def isLocal(uri: Rdf#URI): Boolean = uri.fragmentLess == from

   /** Multi-Located Graphs. This results from a union of a number of Named Graphs.<p> The union of
     * a number of named graphs does not come from one location but different locations. A Named
     * Graph NG is just a special case where the graph comes from only one location.<p> Potentially
     * a more precise way to represent this would be to have a set of quads, and for each triple
     * have a way to find all the places those came from.
     * @param from
     *   each triple can come from one or more of these locations
     */
   case class LGs(from: Set[Rdf#URI], graph: Rdf#Graph) extends WithGraph, Located:
      override def isLocal(uri: Rdf#URI): Boolean =
         val uriNF = uri.fragmentLess
         from.exists(uriNF == _)

   /** A Plain PointedGraph is a graph with one point. But as we are usually following links, we end
     * up with many points, so we take that as the base case, and a plain Pointed Graph then is one
     * with a single Point. It should be easy to make a trait, and create two subclasses when needed
     * for specialised cases.
     *
     * Note: this highlights a "problem" with both: in banana-rdf's PointedGraph, the point can be a
     * node not contained in the graph, and here the list can be empty too. In
     * [[https://github.com/getnelson/quiver Quiver]], on the other hand, a Graph is formed of
     * node-contexts, each node having arrows into and arrows out of a node (See
     * [[https://github.com/banana-rdf/banana-rdf/issues/369 Quiver and RDF]]). One can only get a
     * Graph in a Context of a node - a `GrContext` - if the node exists in the graph. This also
     * works: a Pointed[N] with empty points is just a Located Graph. But it is more awkward if the
     * point does not exist in the NG, as really one should then stop looking immediately. Both
     * those problems mean that Pointed cannot work as a comonad of graph decompositions though.
     */
   case class Pointed[NG <: WithGraph & Located](points: LazyList[Rdf#Node], ng: NG):
      /** search forward. We return a LazyList of results, as opposed to what banana-rdf
        * PointedGraphs that returns an Iterable. The reasoning is the following: often one starts
        * from a node to follow a relation eg {{{val autz = wac.Authorization\-rdf.type}}} This
        * lands one on a sequence of nodes - which are expensive to calculate, but not expensive to
        * remember, as we keep just the nodes names from the triples. Once we have this sequence we
        * may want to run through them a number of times, to filter the sequence by their relation
        * to other nodes and literals in this graph and in other graphs. Using Iterable runs the
        * risk that running through a sequence a couple of times would risk re-calculating the
        * nodes. Todo: verify above reasoning is correct
        */
      def /(rel: Rdf#URI): Pointed[LocatedGraph] =
        Pointed(points.flatMap(node => ng.graph.select(node, rel, ANY).map(_.objectt)), ng)

      def /-(rel: Rdf#URI): Pointed[LocatedGraph] =
        Pointed(points.flatMap(node => ng.graph.select(ANY, rel, node).map(_.subject)), ng)

   object Pointed:
      def apply[NG <: WithGraph & Located](node: Rdf#Node, ng: NG) = new Pointed(LazyList(node), ng)

/** There are many possible wrapper functions for Named Graphs, and so it make sense to seperate
  * them from the data structure. Perhaps a generalisation to a Monad or such will be visible.
  *
  * Here I will add structures for using them with Scripts todo: better name for this object
  */
object LocatedGraphScriptExt:
   import run.cosy.ldp.rdf.LocatedGraphs.{LG, Pointed, WithGraph}
   import run.cosy.ldp.SolidCmd
   import LocatedGraphs.LocatedGraph
   import SolidCmd.Script
   import ops.{given, *}

   extension (pg: Pointed[LocatedGraph])
     def exists(pgProperty: (Rdf#Node, Rdf#Graph) => Boolean): Boolean =
       pg.points.exists(node => pgProperty(node, pg.ng.graph))

//todo: we need to keep track of where we went, to avoid going back on our tracks.
// todo: worry about redirects
// What is a jump on a Pointed[?] since there can be more than one point?
// With a NamedPointedGraph there a jump could lead one to the remote graph at that point
// but if one has a number of points one could end up in a number of graphs.
// A Jump here would need to include the remote graphs
//		def jump: Script[Pointed[LocatedGraph]] =
//			pg.points.map{ point =>
//				pg.ng.ifLocal(point)(SolidCmd.pure(pg)) { uri =>
//					SolidCmd.get(uri.toAkka.withoutFragment).map{ resp =>
//						//todo: what should we do if we have a Failure?
//						//  we can indeed return "this" graph as done here, but
//						//  it feels like there should be a better answer.
//						//todo: also what if there is an error? Should we try again? How often?
//						//  these types of problems will appear again, and again: we need a library for that
//						//todo: Question: It may be better if we worked directly with the Response class
//						resp.content match
//							case Success(g) => Pointed(pg.points, LG(resp.meta.url.toRdf, g))
//							case _ => pg
//					}
//				}
//			}
