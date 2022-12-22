/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http.auth

import akka.http.scaladsl.model.{HttpMethods, Uri}
import run.cosy.RDF
import run.cosy.RDF.*
import run.cosy.RDF.ops.*
import run.cosy.http.auth.Guard.filterRulesFor
import run.cosy.ldp.rdf.LocatedGraphs.LGs
import run.cosy.ldp.{ImportsDLTestServer, TestCompiler, TestServer, WebServers}
import scalaz.Cofree

/* Test Guard on Basic Server */
class TestGuard extends munit.FunSuite:

   import RDF.*
   import RDF.ops.*
   import akka.http.scaladsl.model.HttpMethods.{GET, PUT, POST, DELETE}
   import cats.implicits.*
   import cats.{Applicative, CommutativeApplicative, Eval, *}
   import run.cosy.http.util.UriX.*
   import run.cosy.ldp.SolidCmd.*
   import WebServers.aclBasic
   import aclBasic.ws
   import run.cosy.ldp.SolidCmd

   val rootAcl = ws.base / ".acl"
   val rootUri = ws.base / ""

   val podRdfAcl = rootAcl.toRdf

   val owner      = (ws.base / "owner").withFragment("i")
   val OwnerAgent = WebIdAgent(owner)

   val timBlSpace   = ws.base / "People" / "Berners-Lee" / ""
   val timBlCardUri = ws.base / "People" / "Berners-Lee" / "card"
   val timBl        = timBlCardUri.withFragment("i")

   // "http://localhost:8080/Hello_6"
   test("test access to resources using root container ACL") {

     val aclGraph: LGs = ws.locatedGraphs(rootAcl).get
     val getRules      = Guard.filterRulesFor(aclGraph, rootUri, GET)
     assertEquals(
       getRules.points.toSet,
       Set(podRdfAcl.withFragment("Public"), podRdfAcl.withFragment("Admin"))
     )

     assert(
       Guard.authorize(getRules, new Anonymous),
       "The root acl allows all contents to be READable"
     )

     val fetchedDataSet: ReqDataSet = SolidCmd.fetchWithImports(rootAcl).foldMap(aclBasic.eval)
     val expectedDataSet: ReqDataSet = cats.free.Cofree(
       SolidCmd.Meta(rootAcl),
       cats.Now(SolidCmd.GraF(aclGraph.graph, Set()))
     )
     assertEquals(fetchedDataSet, expectedDataSet)

     assertEquals(unionAll(fetchedDataSet), LGs(Set(rootAcl.toRdf), aclGraph.graph))

     val auth: Boolean = Guard.authorize(getRules, new Anonymous())
     assert(auth, "Anyone can access the root container")

     val aclWithImports: LGs =
       SolidCmd.unionAll(SolidCmd.fetchWithImports(rootAcl).foldMap(aclBasic.eval))
     assertEquals(
       aclWithImports,
       aclGraph,
       "The root graph has no imports so should be the same as fetching it directly"
     )

     val answer: Boolean =
       Guard.authorizeScript(rootAcl, new Anonymous(), rootUri, GET).foldMap(aclBasic.eval)
     assert(answer, "The root acl allows all contents to be READable")

     val answer2 = Guard.authorizeScript(
       rootAcl,
       new Anonymous(),
       ws.base / "Hello_6",
       GET
     ).foldMap(aclBasic.eval)
     assert(answer2, "The root acl allows all contents to be READable")

     val postRules = Guard.filterRulesFor(aclGraph, rootUri, POST)
     assert(
       !Guard.authorize(postRules, Anonymous()),
       "Anonymous should not have POST access"
     )
     val answer2Post =
       Guard.authorizeScript(rootAcl, Anonymous(), rootUri, POST).foldMap(aclBasic.eval)
     assert(!answer2Post, "Anonymous should not have POST access to root container")

     val rootACLGET2 =
       Guard.authorizeScript(rootAcl, WebIdAgent(owner), rootAcl, GET).foldMap(aclBasic.eval)
     assert(
       rootACLGET2,
       "The owner should have read access to root container acl as he has Control rights."
     )

     val rootACLPUT2 =
       Guard.authorizeScript(rootAcl, WebIdAgent(owner), rootAcl, PUT).foldMap(aclBasic.eval)
     assert(
       rootACLPUT2,
       "The owner should have Edit access to root container acl as he has Control rights."
     )

     val answer2Post2 =
       Guard.authorizeScript(rootAcl, WebIdAgent(owner), rootUri, POST).foldMap(aclBasic.eval)
     assert(answer2Post2, "The owner should have POST access to root container.")

     val answer3 =
       Guard.authorizeScript(rootAcl, new Anonymous(), timBlCardUri, GET).foldMap(aclBasic.eval)
     assert(answer3, "Anyone can read Tim Berners-Lee's card")
     val answer3PUT =
       Guard.authorizeScript(rootAcl, WebIdAgent(owner), timBlCardUri, GET).foldMap(aclBasic.eval)
     assert(answer3, "The owner can PUT to Tim Berners-Lee's card")

     val answer4 =
       Guard.authorizeScript(rootAcl, new Anonymous(), timBlCardUri, PUT).foldMap(aclBasic.eval)
     assert(
       !answer4,
       "The root rule applies by default to Tim Berners-Lee's card, disallowing PUT by anonymous"
     )
   }
   
   val readmeACL = (ws.base / "README.acl")
   val readme = (ws.base / "README")
   val indexACL = (ws.base / "index.acl")
   val index    = (ws.base / "index")
 
   test("acl with no owl:imports giving read access to itself") {
     val rmACLGet = Guard.authorizeScript(readmeACL, Anonymous(), readmeACL, GET).foldMap(aclBasic.eval)
     assert(rmACLGet, "/README.acl is readable by all")
     
     val rmACLPUT = Guard.authorizeScript(readmeACL, Anonymous(), readmeACL, PUT).foldMap(aclBasic.eval)
     assert(!rmACLPUT, "/README.acl is not writeable by all")
     
     val rmGET = Guard.authorizeScript(readmeACL, Anonymous(), readme, GET).foldMap(aclBasic.eval)
     assert(rmGET, "/README is readable by all")
     
     val rmPUT = Guard.authorizeScript(readmeACL, Anonymous(), readme, PUT).foldMap(aclBasic.eval)
     assert(!rmPUT, "/README is not Writeable by all")
     
     val wrongGET = Guard.authorizeScript(readmeACL, Anonymous(), index, GET).foldMap(aclBasic.eval)
     assert(!wrongGET, "README.acl does not give any rights to /index")
   }
   
   val hiddenAcl = (ws.base / "aclsHidden"/ "README.acl")
   val readmeNotHidden = (ws.base / "aclsHidden"/ "README")
   
   test("hidden acl with no owl:imports") {
     val readmeACLGet = Guard.authorizeScript(hiddenAcl, Anonymous(), hiddenAcl, GET).foldMap(aclBasic.eval)
     assert(!readmeACLGet, s"$hiddenAcl is not readable by all")
     
     val readmeACLPut = Guard.authorizeScript(hiddenAcl, Anonymous(), hiddenAcl, PUT).foldMap(aclBasic.eval)
     assert(!readmeACLPut, s"$hiddenAcl is not writeable by all")
     
     val readmeGet = Guard.authorizeScript(hiddenAcl, Anonymous(), readmeNotHidden, GET).foldMap(aclBasic.eval)
     assert(readmeGet, s"$hiddenAcl makes $readmeNotHidden readable by all")

     val readmePut = Guard.authorizeScript(hiddenAcl, Anonymous(), readmeNotHidden, PUT).foldMap(aclBasic.eval)
     assert(!readmePut, s"$hiddenAcl does not make $readmeNotHidden writeable by all")
   
   }

   test("test access to /index ") {
     val aGet = Guard.authorizeScript(indexACL, Anonymous(), index, GET).foldMap(aclBasic.eval)
     assert(aGet, "/index is readable by all")
     val aPost = Guard.authorizeScript(indexACL, Anonymous(), index, POST).foldMap(aclBasic.eval)
     assert(!aPost, "/index cannot be appended to by just anyone")
     val ownerPost =
       Guard.authorizeScript(indexACL, OwnerAgent, rootUri, POST).foldMap(aclBasic.eval)
     assert(ownerPost, "owner should be able to create resources on /")
     val ownerGet = Guard.authorizeScript(indexACL, OwnerAgent, index, GET).foldMap(aclBasic.eval)
     assert(ownerPost, "owner can read the resource he created eg. /index")
   }

   val HR        = (ws.base / "People" / "HR").withFragment("i")
   val PeopleCol = (ws.base / "People" / "")
   val PeopleAcl = (ws.base / "People" / ".acl")

   test("test access to resources inside /People/ container") {
     val aGet = Guard.authorizeScript(PeopleAcl, Anonymous(), PeopleCol, GET).foldMap(aclBasic.eval)
     assert(aGet, "All content in /People/ is readable by all")

     val aPost =
       Guard.authorizeScript(PeopleAcl, WebIdAgent(owner), PeopleCol, POST).foldMap(aclBasic.eval)
     assert(aPost, "Content in /People/ can be created by the owner")

     val aPost2 =
       Guard.authorizeScript(PeopleAcl, WebIdAgent(HR), PeopleCol, POST).foldMap(aclBasic.eval)
     assert(aPost2, "Content in /People/ can be created by the owner")

     val aPost3 =
       Guard.authorizeScript(PeopleAcl, WebIdAgent(HR), rootUri, POST).foldMap(aclBasic.eval)
     assert(!aPost3, "HR can NOT create new containers in root /")

     val aPost4 =
       Guard.authorizeScript(PeopleAcl, WebIdAgent(HR), timBlSpace, POST).foldMap(aclBasic.eval)
     assert(aPost4, "HR would have access to TimBL's space, if his ACL imported the /People/.acl")

     val aPost5 =
       Guard.authorizeScript(PeopleAcl, WebIdAgent(timBl), PeopleCol, POST).foldMap(aclBasic.eval)
     assert(!aPost5, "TimBL does not have access to create resources in the /People/ space")

   }

   val timBlSpaceAcl = ws.base / "People" / "Berners-Lee" / ".acl"

   test("test access to resources inside /People/Berners-Lee/ container") {
     val aGet =
       Guard.authorizeScript(timBlSpaceAcl, Anonymous(), timBlSpace, GET).foldMap(aclBasic.eval)
     assert(aGet, "All content in /People/Berners-Lee/ is readable by all")

     val aPost = Guard.authorizeScript(timBlSpaceAcl, WebIdAgent(timBl), timBlSpace, POST).foldMap(
       aclBasic.eval
     )
     assert(aPost, "TimBL does have rights to POST in his own space")

     val aPost2 =
       Guard.authorizeScript(timBlSpaceAcl, WebIdAgent(HR), timBlSpace, POST).foldMap(aclBasic.eval)
     assert(
       !aPost2,
       "but HR cannot Post into TimBL's space as TimBL's .acl does not have an :imports link to the parent one"
     )

     val aPost3 = Guard.authorizeScript(timBlSpaceAcl, WebIdAgent(owner), timBlSpace, POST).foldMap(
       aclBasic.eval
     )
     assert(
       aPost3,
       "but the owner can Post into TimBL's space " +
         "as TimBL's .acl does  have an :imports link the root acl"
     )
   }

   test("test access to root container resource") {
     import WebServers.importsDL
     import importsDL.ws
     val rootAcl = ws.base / ".acl"
     val rootUri = ws.base / ""

     val podRdf    = ws.base.toRdf
     val podRdfAcl = rootAcl.toRdf

     val aclGraph = ws.locatedGraphs(rootAcl).get
     assertEquals(
       Guard.filterRulesFor(aclGraph, rootUri, GET).points.toSet,
       Set(podRdfAcl.withFragment("Public"))
     )
     val answer =
       Guard.authorizeScript(rootAcl, new Anonymous(), rootUri, GET).foldMap(importsDL.eval)
     assert(answer, true)
   }
