/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp.fs

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, scaladsl}
import akka.http.scaladsl.common.StrictForm.FileData
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.ContentTypes.{`text/html(UTF-8)`, `text/plain(UTF-8)`}
import akka.http.scaladsl.model.HttpCharsets.`UTF-8`
import akka.http.scaladsl.model.MediaTypes.{`text/plain`, `text/x-java-source`}
import akka.http.scaladsl.model.StatusCodes.*
import akka.http.scaladsl.model.headers.*
import akka.http.scaladsl.server.ContentNegotiator.Alternative.ContentType
import akka.stream.scaladsl.FileIO
import akka.stream.{IOResult, Materializer}
import org.eclipse.rdf4j.model.impl.DynamicModel
import org.eclipse.rdf4j.model.{IRI, Resource, Value}
import org.eclipse.rdf4j.rio.{RDFFormat, RDFWriter, Rio}
import run.cosy.RDF.*
import run.cosy.RDF.ops.*
import run.cosy.http.RDFMediaTypes.*
import run.cosy.http.auth.{KeyIdAgent, WebServerAgent}
import run.cosy.http.util.UriX.*
import run.cosy.http.{FileExtensions, RDFMediaTypes, RdfParser}
import run.cosy.ldp.ACInfo
import run.cosy.ldp.Messages.*
import run.cosy.ldp.SolidCmd.Plain
import run.cosy.ldp.fs.BasicContainer.{LDPLinkHeaders, PostCreation}
import run.cosy.ldp.fs.Resource.{StateSaved, connegNamesFor, extension, headersFor}
import run.cosy.ldp.{ResourceRegistry, fs}

import java.io.{ByteArrayOutputStream, OutputStreamWriter, StringWriter}
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.{BasicFileAttributes, FileAttribute}
import java.nio.file.{CopyOption, Files, Path as FPath}
import java.security.Principal
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

//import akka.http.scaladsl.model.headers.{RequestClientCertificate, `Tls-Session-Info`}

object Resource:

   import akka.stream.Materializer
   import run.cosy.RDF.Prefix.ldp

   import java.nio.file.Path
   import scala.concurrent.ExecutionContext

   type AcceptMsg = ScriptMsg[?] | Do | WannaDo | PostCreation | StateSaved

   def apply(rUri: Uri, linkName: FPath, name: String, defaultACL: ACInfo): Behavior[AcceptMsg] =
     Behaviors.setup[AcceptMsg] { (context: ActorContext[AcceptMsg]) =>
       // val exists = Files.exists(root)
       //			val registry = ResourceRegistry(context.system)
       //			registry.addActorRef(rUri.path, context.self)
       //			context.log.info("started LDPR actor at " + rUri.path)
       new Resource(rUri, linkName, context).behavior(defaultACL)
     }

//  @throws[Exception](classOf[Exception])
//  def preStart() = {
//    log.info(s"starting guard for $ldprUri ")
//    guard = context.actorOf(Props(new Guard(ldprUri,List())))
//  }
   case class StateSaved(at: Path, cmd: CmdMessage[?])

   def mediaType(path: FPath): MediaType = FileExtensions.forExtension(extension(path))

   val AllowHeader =
      import HttpMethods.*
      Allow(GET, PUT, DELETE, HEAD, OPTIONS) // add POST for Solid Resources

   def extension(path: FPath) =
      val file = path.getFileName.toString
      var n    = file.lastIndexOf('.')
      if n > 0 then file.substring(n + 1) else ""

   def headersFor(att: BasicFileAttributes): List[HttpHeader] =
     eTag(att) :: lastModified(att) :: Nil

   def eTag(att: BasicFileAttributes): ETag =
      import att.*
      // todo: rather than the file key, which contains an inode number, one could just use the version name
      ETag(s"${lastModifiedTime.toMillis}_${size}_${fileKey.hashCode()}")

   def lastModified(att: BasicFileAttributes): HttpHeader =
      import akka.http.scaladsl.model.headers.`Last-Modified`
      `Last-Modified`(DateTime(att.lastModifiedTime().toMillis))

   /** Return the link stating that the subject is a an LDPR.
    * If None, then the subject is the resource that returns the resource.
     */
   def LDPR(subject: Option[Uri] = None): LinkValue =
     LinkValue(run.cosy.ldp.fs.BasicContainer.ldpr,
       LinkParams.rel("type") :: subject.toList.map(LinkParams.anchor))

   /** todo: language versions, etc...
     */
   def connegNamesFor(name: String, ct: `Content-Type`): List[String] =
     ct.contentType.mediaType.fileExtensions.map(name + "." + _)

   import org.w3.banana.PointedGraphs
   import run.cosy.http.auth.Agent
   import run.cosy.ldp.SolidCmd.ReqDataSet

end Resource

import run.cosy.ldp.fs.BasicContainer.PostCreation
import run.cosy.ldp.fs.Resource.AcceptMsg

class Resource(uri: Uri, linkPath: FPath, context: ActorContext[AcceptMsg])
    extends ResourceTrait(uri, linkPath, context):
   import run.cosy.ldp.fs.Resource.LDPR

   def archivedBehavior(linkedToFile: String): Option[Behaviors.Receive[AcceptMsg]] =
     if linkedToFile.endsWith(".archive") then Some(GoneBehavior)
     else None

   override def linkDoesNotExistBehavior: Behaviors.Receive[AcceptMsg] =
     Behaviors.receiveMessage[AcceptMsg] { (msg: AcceptMsg) =>
        msg match
         case cmd: Route =>
           cmd.msg.respondWith(HttpResponse(NotFound))
         case PostCreation(_, cmsg) =>
           context.log.warn(
             s"received Create on resource <$uri> at <$linkPath> that does not have a symlink! " +
               s"Message should not reach this point."
           )
           cmsg.respondWith(HttpResponse(
             InternalServerError,
             entity = HttpEntity("please contact admin")
           ))
         case StateSaved(_, cmsg) =>
           context.log.warn(
             s"received a state saved on source <$uri> at <$linkPath> that has been does not have a symlink!"
           )
           cmsg.respondWith(HttpResponse(
             InternalServerError,
             entity = HttpEntity("please contact admin")
           ))
         case script: ScriptMsg[?] =>
           import script.given
           script.continue
        Behaviors.stopped
     }

   // if we have a symbolic link that is linking to `linkTo`
   @throws[SecurityException]
   override def linkedToFileDoesNotExist(
       linkTo: String,
       acinfo: ACInfo
   ): Option[Behaviors.Receive[AcceptMsg]] =
     if !Files.exists(linkPath.resolveSibling(linkTo)) then Some(justCreatedBehavior(acinfo))
     else None

   def VersionsInfo(lastVersion: Int, linkTo: FPath, defaultAC: ACInfo): VersionsInfo =
      val aclLinkExists: Boolean = Files.exists(linkPath.resolveSibling(aclName))
      import ACInfo.*
      val acAct: ACInfo = aclActor(aclLinkExists) match
       case None        => defaultAC
       case Some(acRef) => ACtrlRef(uri, aclName, acRef, false)
      new VersionsInfo(lastVersion, linkTo, acAct) {}

   override def aclLinks(acl: Uri, active: ACInfo): List[LinkValue] =
     LinkValue(acl, LinkParams.rel("acl")) :: {
       def lv = LinkValue(active.container, LinkParams.rel(BasicContainer.defaultACLink)) :: Nil
       active match
        case ACInfo.ACtrlRef(container,acl,_,isContainer) if container == uri => Nil
        case _ => lv
     }

/** An LDPR created with POST is only finished when the content has downloaded (and potentially
     * been verified)
     */
   def justCreatedBehavior(acInfo: ACInfo) =
     Behaviors.receiveMessage[AcceptMsg] { (msg: AcceptMsg) =>
       msg match
        case cr: PostCreation =>
          BuildContent(cr)
          Behaviors.same
        case StateSaved(null, cmd) =>
          // ACR: what happens if the ACL creation fails?! (That makes the resource innaccessible....)
          // If we can't save the body of a POST then the POST fails
          // note: DELETE can make sense in so far as the default behavior is just to return the imports of parent acl.
          // todo: the parent should also be notified... (which it will with stopped below, but enough?)
          Files.delete(linkPath)
          // is there a more specific error to be had?
          cmd.respondWith(HttpResponse(
            StatusCodes.InternalServerError,
            entity = HttpEntity("upload unsucessful")
          ))
          Behaviors.stopped
        case StateSaved(pos, cmd) =>
          // todo: we may only want to check that the symlinks are correct
          import Resource.*

          import java.nio.file.StandardCopyOption.ATOMIC_MOVE
          val tmpSymLinkPath = Files.createSymbolicLink(
            linkPath.resolveSibling(pos.getFileName.toString + ".tmp"),
            pos.getFileName
          )
          Files.move(tmpSymLinkPath, linkPath, ATOMIC_MOVE)
          val att = Files.readAttributes(pos, classOf[BasicFileAttributes])
          cmd.respondWith(HttpResponse(
            Created,
            Location(uri) :: Link(
              LDPR(Some(uri)) :: aclLinks(aclUri, acInfo )
            ) :: AllowHeader :: headersFor(att),
            HttpEntity(`text/plain(UTF-8)`, s"uploaded ${att.size()} bytes")
          ))
          behavior(acInfo)
        case route: Route =>
          // todo: here we could change the behavior to one where requests are stashed until the upload
          // is finished, or where they are returned with a "please wait" response...
          route.msg.respondWith(HttpResponse(
            Conflict,
            entity = HttpEntity("the resource is not yet completely uploaded. Try again later.")
          ))
          Behaviors.same
        case script: ScriptMsg[?] =>
          import script.given
          script.continue
          Behaviors.same
     }
