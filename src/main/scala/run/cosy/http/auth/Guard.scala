/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http.auth

import akka.http.scaladsl.model.{HttpMethod, Uri}
import org.w3.banana.{PointedGraphs, WebACLPrefix}
import run.cosy.ldp.SolidCmd.ReqDataSet
import akka.actor.typed.scaladsl.ActorContext
import akka.http.scaladsl.model.headers.{Link, LinkParam, LinkParams}
import run.cosy.ldp.Messages.CmdMessage
import run.cosy.ldp.rdf.LocatedGraphs.{LGs, LocatedGraph, PtsLGraph, Pointed}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration

object Guard:

   import run.cosy.http.auth.WebIdAgent
   import akka.actor.typed.scaladsl.Behaviors
   import akka.http.scaladsl.model.HttpMethods.*
   import akka.http.scaladsl.model.headers.{`WWW-Authenticate`, HttpChallenge}
   import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
   import org.w3.banana.diesel.PointedGraphW
   import org.w3.banana.PointedGraph
   import org.w3.banana.syntax.{GraphW, NodeW}
   import run.cosy.RDF.*
   import run.cosy.RDF.ops.*
   import run.cosy.RDF.Prefix.{security, wac, foaf}
   import run.cosy.http.util.UriX.*
   import run.cosy.ldp.Messages.{Do, ScriptMsg, WannaDo}
   import run.cosy.ldp.SolidCmd.{fetchWithImports, unionAll, Get, Plain, Script, Wait}
   import run.cosy.ldp.SolidCmd
   import run.cosy.ldp.rdf.LocatedGraphScriptExt.*

   import scala.util.{Failure, Success}

   type GMethod = GET.type | PUT.type | POST.type | DELETE.type // | PATCH.type

   /** Determine if an agent is authorized to request the operation on the target resource, given
     * the graph of ACL rules.
     *
     * Note that as discussed in
     * [[https://github.com/solid/authorization-panel/discussions/223 Thinking in terms of proofs and Origins]]
     * a more sophisticated implementation may want to also know more about the proof that `agent`
     * is making the request: e.g. was the agent talking via another Origin? We may also end up with
     * not being given an Agent but a partial description of an Agent as given by a certificate, and
     * where one would need to verify if that partial description fit a rule.
     *
     * todo: Also the answer could be a richer proof object: perhaps one showing exactly which quads
     * were used to come to the conclusion. Even better: perhaps a sequence of steps that led from
     * the rules to the data would be better. Indeed it would be very important for debugging.
     */
   def authorize(acg: LGs, agent: Agent, target: Uri, operation: GMethod): Boolean =
      val rules = filterRulesFor(acg, target, operation)
      def ac: Boolean = (rules / wac.agentClass).points.exists {
        _ == foaf.Agent // || todo: fill in authenticated agent and groups
      }
      def agents: Pointed[LocatedGraph] = rules / wac.agent
      def groups: Pointed[LocatedGraph] = rules / wac.agentGroup
      def ag: Boolean =
        agent match
        case WebIdAgent(id) =>
          val webId = id.toRdf
          agents.points.exists(_ == webId)
        case KeyIdAgent(keyId, _) =>
          agents.exists((node, graph) =>
            graph.select(keyId.toRdf, security.controller, node).hasNext
          )
        case _ => false
      ac || ag
   end authorize

   /** From a graph of rules, return a stream of pointers to the rules that apply to the given
     * target and operation.<p> The only description supported to start with is acl:default, and we
     * test this by substring on the path of the URL. todo: we don't deal with rules that specify
     * the target by description, which would require looking up metadata on the target URI to check
     * if it matches the description. Doing that would require having information for the target
     * resource, or fetching such information... Fetching would mean this would need to be a Script?
     *
     * @param acRulesGraph
     */
   def filterRulesFor(acRulesGraph: LGs, target: Uri, operation: GMethod): PtsLGraph =
      val targetRsrc: Rdf#URI = target.toRdf
      // we assume that all rules are typed
      val rules: PtsLGraph = Pointed(wac.Authorization, acRulesGraph) /- rdf.`type`
      // todo: add filter to banana-rdf
      val nodes: LazyList[Rdf#Node] = rules.points.filter { (node: Rdf#Node) =>
        modesFor(operation).exists(mode =>
          acRulesGraph.graph.select(node, wac.mode, mode).hasNext
        ) &&
        (acRulesGraph.graph.select(node, wac.accessTo, targetRsrc).hasNext ||
          acRulesGraph.graph.select(node, wac.default, ANY).exists { (tr: Rdf#Triple) =>
            tr.objectt.fold(u => u.toAkka.ancestorOf(target), x => false, z => false)
          })
      }
      Pointed(nodes, acRulesGraph)
   end filterRulesFor

   /** Modes needed for the given HTTP Method. Control always gives all rights, so always return it.
     */
   def modesFor(op: GMethod): List[Rdf#URI] =
      import run.cosy.ldp.SolidCmd
      val mainMode = op match
      case GET    => wac.Read
      case PUT    => wac.Write
      case POST   => wac.Write
      case DELETE => wac.Write
      List(mainMode, wac.Control)

   def authorizeScript(
       aclUri: Uri,
       agent: Agent,
       target: Uri,
       method: HttpMethod
   ): Script[Boolean] =
      import akka.actor.typed.Behavior
      for
         reqDS <- fetchWithImports(aclUri)
      yield method match
      case m: GMethod => authorize(unionAll(reqDS), agent, target, m)
      case _          => false

   def aclLink(acl: Uri): Link = Link(acl, LinkParams.rel("acl"))

   /** we authorize the top command `msg` - it does not really matter what T the end result is. */
   def Authorize[T](msg: CmdMessage[T], aclUri: Uri)(using
       context: ActorContext[ScriptMsg[?] | Do]
   ): Unit =
      // todo!!: set timeout in config!!
      given timeOut: akka.util.Timeout = akka.util.Timeout(Duration(3, TimeUnit.SECONDS))
      context.log.info(s"Authorize(WannaDo($msg), <$aclUri>)")
      import SolidCmd.{Get, Wait}
      import run.cosy.http.auth.Guard.*
      import cats.free.Free.pure
      if msg.from == WebServerAgent then
         context.self ! Do(msg)
      else
         msg.commands match
         case p: Plain[?] =>
           import msg.given
           // this script should be sent with admin rights.
           // note: it could also be sent with the rights of the user, meaning that if the user cannot
           //   read an acl rule, then it has no access. But that would slow things down as it would require
           //   every request on every acl to be access controlled!
           context.ask[ScriptMsg[Boolean], Boolean](
             context.self,
             ref =>
               ScriptMsg[Boolean](
                 authorizeScript(aclUri, msg.from, msg.target, p.req.method),
                 WebServerAgent,
                 ref
               )
           ) {
             case Success(true) =>
               context.log.info(s"Successfully authorized ${msg.target} ")
               Do(msg)
             case Success(false) =>
               context.log.info(s"failed to authorize ${msg.target} ")
               msg.respondWithScr(HttpResponse(
                 StatusCodes.Unauthorized,
                 Seq(
                   aclLink(aclUri),
                   `WWW-Authenticate`(HttpChallenge("Signature", s"${msg.target}"))
                 )
               ))
             case Failure(e) =>
               context.log.info(s"Unable to authorize ${msg.target}: $e ")
               msg.respondWithScr(HttpResponse(
                 StatusCodes.Unauthorized,
                 Seq(
                   aclLink(aclUri),
                   `WWW-Authenticate`(HttpChallenge("Signature", s"${msg.target}"))
                 ),
                 HttpEntity(ContentTypes.`text/plain(UTF-8)`, e.getMessage)
               ))
           }
         case _ => // the other messages end up getting translated to Plain reuests . todo: check
           context.self ! Do(msg)
   end Authorize

end Guard
