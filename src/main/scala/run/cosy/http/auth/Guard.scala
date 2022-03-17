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
import cats.free.Free
import run.cosy.ldp.Messages.CmdMessage
import run.cosy.ldp.rdf.LocatedGraphs.{LGs, LocatedGraph, Pointed, PtsLGraph}

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

   /** Determine if an agent is authorized for the identified rules. The data is assumed to be all
     * present in the LGs.
     * @param rules
     *   Points in a Located Graph that identify rules valid for the request that agent is making
     * @param agent
     *   the agent authenticating
     * @return
     *   true if the agent can authenticate
     */
   def authorize(rules: PtsLGraph, agent: Agent): Boolean =
      def publicResource: Boolean = (rules / wac.agentClass).points.exists {
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
      publicResource || ag
   end authorize

   /** See
     * [[https://github.com/co-operating-systems/Reactive-SoLiD/blob/work/src/main/scala/run/cosy/http/auth/Auth.md Auth.md]]
     * for details on how to understand an authorization that uses a client supplied proof. Because
     * we are verifying a proof, we reduce the amount of requrests made dramatically to just
     * following the needed links. Hence the script.
     *
     * @param rules
     *   Pointed Located Graph with points the wac:Authorization rules that are valid for the
     *   request and method. All that remains to be done is test that the agent can access them
     * @param agent
     *   the agent that is asking for access, identified by some property as keyID.
     * @param hints
     *   the client supplied reason for believing access is allowed
     * @return
     *   A Script of a boolean if access is allowed. For debugging Try[Unit] may be better, as that
     *   would allow an error explaining where the problem occurred to be returned for debugging.
     *   (non existent resource? etc...)
     */
   def authorizeScript(
       rules: PtsLGraph,
       agent: Agent,
       hints: WacHint.Path
   ): Script[Boolean] =
      // both sanity checks assume the path is not empty
      import WacHint.*
      def sanityCheckHead: Boolean =
        hints.path.head._1 match
         case NNode(ruleName) => rules.points.contains(ruleName)
         case bn: BNode       => rules.points.exists(n => n.isBNode)
      def sanityCheckLast: Boolean =
         val agentId = hints.path.last._3
         agent match // todo: deal with more cases such as OpenID, ...
          case KeyIdAgent(keyIdUri, _) => agentId == keyIdUri
          case _                       => false

      if hints.path.isEmpty
      then Free.pure(authorize(rules, agent))
      else if !sanityCheckHead && !sanityCheckLast then
         Free.pure(false)
      else
         // hints.path
         // we want to walk through the path starting from the first node (which if blank means we start from all of them)
         ???

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
       case m: GMethod =>
         authorize(filterRulesFor(unionAll(reqDS), target, m), agent)
       case _ => false

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
