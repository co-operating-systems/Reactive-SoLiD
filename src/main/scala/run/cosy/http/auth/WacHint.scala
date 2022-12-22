/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http.auth

import cats.data.NonEmptyList
import run.cosy.RDF.*
import run.cosy.RDF.ops.*

import scala.collection.immutable.List
import scala.util.Try

object WacHint:
   type Triple = (Node, Arrow, Node)

   /** Client hint to Guard on the path to solve to get from the rule to the authenticated key
     *
     * @param path
     *   node followed by relation followed by node ...
     */
   case class Path private (path: List[Triple]):
      /* group the path into paths that must follow inside the graph of one resource, so that
       different paths can be followed in parallel */
      def groupByResource: List[NonEmptyList[Triple]] =
         val r = path.foldLeft(List[NonEmptyList[Triple]]()) { (pathsInGraph, triple) =>
            def append(triple: Triple) = pathsInGraph.head.prepend(triple) :: pathsInGraph.tail
            def isInPathGraph(url: Rdf#URI): Boolean =
              pathsInGraph.head.last._1 match
               case _: BNode        => false
               case NNode(startUrl) => startUrl.fragmentLess == url.fragmentLess

            if pathsInGraph.isEmpty then List(NonEmptyList.one(triple))
            else
               val lastNode = pathsInGraph.head.head._3
               lastNode match
                case x: BNode                         => append(triple)
                case NNode(url) if isInPathGraph(url) => append(triple)
                case _                                => NonEmptyList.one(triple) :: pathsInGraph
         }
         r.reverse.map(_.reverse) // todo: optimize the above to avoid this (no time now)

   object Path:
      /** make the links form a chain of arrows and single objects */
      def apply(path: Step*): Option[Path] =
        path.toList.sliding(3, 2).foldRight(Option(List[Triple]())) { case (tripleAsList, answer) =>
          tripleAsList match
           case List(subj: Node, rel: Arrow, obj: Node) =>
             answer.map(l => (subj, rel, obj) :: l)
           case _ => None
        }.map(new Path(_))
   end Path

   sealed trait Step
   /*
    * Arrows, Relations that can jump from one document to another.
    * By default relations jump to the resource they point to (later we can add attributes to change this behavior)
    * If we have a reason then the the URI identifies the relation to be followed in the rule
    * but the reason gives how that relation is to be expanded into a path of existing links
    * E.g. if anyone is allowed who Tim knows  and this class is named `<#kT>` then if bbl is authenticating
    * we need the reverse relation  `:bbl rdf:type <#kT>`.
    * We very likely won't find that statement in the document though. Instead we need to find a relation
    * `:timbl foaf:knows :bbl` from a document that is trusted, which would be either :timbl's WebID profile
    *  or a document linked to by rdfs:seeAlso from his WebID.
    * (Note that there could be other verifiable claims methods of verifying something)
    */
   trait Arrow(name: Rdf#URI, reason: Option[Step] = None) extends Step
   trait Node                                              extends Step

   case class Rel(name: Rdf#URI, reason: Option[Step] = None) extends Arrow(name, reason)

   /*  Reverse relations e.g. Rev(father) is Rel(fatherOf), here we will just search from the object
     back to the subject */
   case class Rev(name: Rdf#URI, reason: Option[Step] = None) extends Arrow(name, reason)

   // Named Node
   case class NNode(name: Rdf#URI) extends Node
   case class BNode()              extends Node

end WacHint
