/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp.fs

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.ContentTypes.{`text/html(UTF-8)`, `text/plain(UTF-8)`}
import akka.http.scaladsl.model.HttpCharsets.`UTF-8`
import akka.http.scaladsl.model.MediaTypes.`text/x-java-source`
import akka.http.scaladsl.model.StatusCodes.{Gone, InternalServerError, MethodNotAllowed, NoContent}
import akka.http.scaladsl.model.headers.*
import akka.stream.IOResult
import akka.stream.scaladsl.FileIO
import run.cosy.ldp.{ACInfo, SolidPostOffice}
import run.cosy.ldp.ACInfo.ACContainer
import run.cosy.ldp.Messages.*
import run.cosy.http.util.UriX.*
import run.cosy.ldp.fs.BasicContainer.{PostCreation, aclLinks}
import run.cosy.ldp.fs.Resource.{AcceptMsg, StateSaved, extension, headersFor}

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{CopyOption, Files, Path as FPath}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** LDPR Actor extended with Access Control. The LDPR Actor should keep track of states for a
  * resource, such as previous versions, and variants (e.g. human translations, or just different
  * formats for the same content). The LDPR actor deals with creation and update to resources.
  *
  * A Resource can react to
  *   - GET and do content negotiation
  *   - PUT for changes
  *   - PATCH and QUERY potentially
  *
  * An LDPR actor should garbage collect after inactive time, to reduce memory usage.
  *
  * This implementation makes a number of arbitrary decisions.
  *   1. Resources are symlinks pointing to default representations 2. ...
  *
  * todo: do we really next to pass context?
  */
trait ResourceTrait(uri: Uri, linkPath: FPath, context: ActorContext[AcceptMsg]):
   import Resource.{LDPR, mediaType}
   import context.log
   import run.cosy.http.auth.Agent
   import run.cosy.ldp.SolidCmd
   import run.cosy.ldp.SolidCmd.ReqDataSet

   given po: SolidPostOffice = SolidPostOffice(context.system)

   val linkName = linkPath.getFileName.toString
   // todo. replace with DotFileName
   final lazy val dotLinkName: Dot = Dot(linkName)
   // we have only one level of acls.
   def aclName: String = if dotLinkName.isACR then linkName else linkName + ".acl"
   import run.cosy.http.util.UriX.sibling
   val aclUri = uri.sibling(aclName)

   // todo: this actor needs to act like a container and receive cancelation messages from the acr
   //  to reset it
   var aclActorDB: Option[ActorRef[AcceptMsg]] = None

   /** calcuate the Link: <doc.acl>;rel="acl", and </>;rel="defaultAccessContainer" relations given
     * the active ACInfo, which will be set to the <$aclUri>;rel=acl only if a local acl resource
     * exists.
     */
   def aclLinks(active: ACInfo): List[LinkValue] = BasicContainer.aclLinks(uri, aclUri, active)

   def aclActor(create: Boolean): Option[ActorRef[AcceptMsg]] =
      if aclActorDB.isEmpty && create then
         val acn = aclName
         aclActorDB =
           Some(context.spawn(ACResource(uri, aclUri, linkPath.resolveSibling(acn), aclName, false),
             acn))
      aclActorDB

   given ac: ActorContext[ScriptMsg[?] | Do] = context.asInstanceOf[ActorContext[ScriptMsg[?] | Do]]

   def behavior(acinfo: ACInfo): Behaviors.Receive[AcceptMsg] =
     try
        import scala.util
        import scala.util.Try
        context.log.info(s"Starting Resource Actor for <$uri> and file $linkPath ")
        // the readSymbolicLink throws all the outer exceptions
        val relativeLinkToPath: FPath = Files.readSymbolicLink(linkPath)
        // if we are here then the symbolic link exists
        val linkTo: FPath = linkPath.resolveSibling(relativeLinkToPath)

        val linkToFileName = linkTo.getFileName.toString
        archivedBehavior(linkToFileName).orElse {
          linkedToFileDoesNotExist(linkToFileName, acinfo)
        }.orElse {
          Try {
            linkToFileName match
             case dotLinkName.File(version, extension) =>
               VersionsInfo(version, linkTo, acinfo).NormalBehavior
             case _ =>
               fileSystemProblemBehavior(new Exception("Storage problem with " + linkToFileName))
          }.toOption
        }.get
     catch // TODO: clean this up!
        case nsfe: java.nio.file.NoSuchFileException => linkDoesNotExistBehavior
        case nle: java.nio.file.NotLinkException     => linkDoesNotExistBehavior
        case uoe: UnsupportedOperationException      => fileSystemProblemBehavior(uoe)
        case se: SecurityException                   => fileSystemProblemBehavior(se)
        case io: java.io.IOException                 => linkDoesNotExistBehavior

   /** The resource is archived todo: a generalisation may prefer to search for this in file
     * attributes
     * @param linkTo
     *   the path the link is pointing to. This works because we here assume the info is in the
     *   name.
     * @return
     *   The behavior for archived resources
     */
   def archivedBehavior(linkTo: String): Option[Behaviors.Receive[AcceptMsg]]

   /** todo: a generalisation would want to abstract from link implementation Behavior to return if
     * the symbolic link itself does not exist
     */
   def linkDoesNotExistBehavior: Behaviors.Receive[AcceptMsg]

   // if we have a symbolic link that is linking to `linkTo`
   @throws[SecurityException]
   def linkedToFileDoesNotExist(
       linkTo: String,
       defaultAC: ACInfo
   ): Option[Behaviors.Receive[AcceptMsg]]

   // todo: This behavior is not fined grained enough. What if there is
   def fileSystemProblemBehavior(e: Exception): Behaviors.Receive[AcceptMsg] =
     Behaviors.receiveMessage[AcceptMsg] { (msg: AcceptMsg) =>
        msg match
         case cmd: Route =>
           cmd.msg.respondWith(
             HttpResponse(
               InternalServerError,
               entity =
                 HttpEntity(`text/x-java-source`.withCharset(HttpCharsets.`UTF-8`), e.toString)
             )
           )
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

   /** All transformations come in two phases (it seems)
     *   1. creating a new name (to save data to)
     *   1. filling in the data by transformation from the old resource to the new one. the
     *      transformation can be just to copy all the data, or to PATCH the old resource, or DELETE
     *      the last one giving a new end of state.
     *
     * Here we start seeing the beginning of such generality, but as we don't have PATCH we only see
     * the creation of a new version - either the initial version, or the next version. The above
     * also works with delete, if we think of it as moving everything to an invisible archive
     * directory.
     *
     * @param cr
     *   the PostCreation message (still looking for a better name)
     */
   def BuildContent(cr: PostCreation): Unit =
      import run.cosy.ldp.SolidCmd.Plain
      val PostCreation(linkToPath, cmd) = cr
      import cmd.{*, given}
      cmd.commands match
       case Plain(req, k) =>
         log.info(
           s"""received POST request with headers ${req.headers} and CT=${req.entity.contentType}
              |Saving to: $linkToPath""".stripMargin
         )
         given sys: ActorSystem[Nothing] = context.system
         val f: Future[IOResult]         = req.entity.dataBytes.runWith(FileIO.toPath(linkToPath))
         context.pipeToSelf(f) {
           case Success(IOResult(count, _)) => StateSaved(linkToPath, cmd)
           case Failure(e) =>
             log.warn(s"Unable to prcess request $cr. Deleting $linkToPath")
             // actually one may want to allow the client to continue from where it left off
             // but until then we delete the broken resource immediately
             Files.deleteIfExists(linkToPath)
             StateSaved(null, cmd)
         }
       case _ => log.warn("not implemented Resource.BuildContent for non Plain requests")

   // todo: Gone behavior may also stop itself earlier
   def GoneBehavior: Behaviors.Receive[AcceptMsg] =
     Behaviors.receiveMessage[AcceptMsg] { (msg: AcceptMsg) =>
        msg match
         case cmd: Route => cmd.msg.respondWith(HttpResponse(Gone))
         case PostCreation(_, cmsg) =>
           log.warn(s"received Create on resource <$uri> at <$linkPath> that has been deleted! " +
             s"Message should not reach this point.")
           cmsg.respondWith(HttpResponse(
             InternalServerError,
             entity = HttpEntity("please contact admin")
           ))
         case StateSaved(_, cmsg) =>
           log.warn(
             s"received a state saved on source <$uri> at <$linkPath> that has been deleted!"
           )
           cmsg.respondWith(HttpResponse(
             InternalServerError,
             entity = HttpEntity("please contact admin")
           ))
         case script: ScriptMsg[?] =>
           import script.given
           script.continue
           Behaviors.same
        Behaviors.same
     }

   /** Aiming for [[https://tools.ietf.org/html/rfc7089 RFC7089 Memento]] perhaps? See work on a
     * [[http://timetravel.mementoweb.org/guide/api/ memento ontology]] see
     * [[https://groups.google.com/g/memento-dev/c/xNHUXDxPxaQ/m/DHG26vzpBAAJ 2017 discussion]] and
     * [[https://github.com/fcrepo/fcrepo-specification/issues/245#issuecomment-338482569 issue]].
     *
     * How much of memento can we implement quickly to get going, in order to test some other
     * aspects such as access control? This looks like a good starting point:
     * [[https://tools.ietf.org/html/rfc5829 RFC 5829 Link Relation Types for Simple Version Navigation between Web Resources]].
     *
     * We start by having one root variant for a resource, which can give rise to other variants
     * built from it automatically (e.g. Turtle variant can give rise to RDF/XML, JSON/LD, etc...),
     * but we keep the history of the principal variant. todo: for now, we assume that we keep the
     * same mime types throughout the life of the resource. allowing complexity here, tends to make
     * one want to open the resource into a container like structure, as it would allow one to get
     * all the resources together. So we start like this:
     *
     *   1. Container receives a POST (or PUT) for a new resource
     *      i. `<card> -> <card>` create loop symlink (or we would need to point `<card>` to
     *         something that does not exist, or that is only partially uploaded, ...)
     *      i. create file `<card.0.ttl>` and pour in the content from the POST or PUT
     *      i. when finished relink `<card> -> <card.0.tll>`
     *
     *   1. UPDATING WITH PUT/PATCH follows a similar procedure
     *      i. `card -> card.v0.ttl` starting point
     *      i. create `<card.1.ttl>` and save new data to that file
     *      i. when finished relink `<card> -> <card.1.ttl>`
     *      i. `<card.v1.ttl>` can have a `Link: ` header pointing to previous version
     *         `<card.0.ttl>`.
     *
     * Later we may want to allow upload of say humanly translated variants of some content.
     *
     * Note: there are many ways to do this, and there is no reason one could not have different
     * implementations. Some different ideas are:
     *   - Metadata in files + one can place metadata into Attributes (Java supports that) + or one
     *     can place metadata into a conventionally named RDF file (with info of how the different
     *     versions are connected)
     *   - Place variants in a directory to speed up search for variants
     *   - specialised versioning File Systems
     *   - implement this over CVS or git, ...
     *
     * Here we will assume that the content of the dir is following the convention
     * content.$num.extension
     *
     * @param lastVersion
     *   max version of this resource. The one the link is pointing to
     * @param linkTo
     *   the path of the latest reference version
     * @param attr
     *   basic file attributes of the latest version (do we really need this?)
     * @param ct:
     *   content type of all the default versions -- very limiting to start off with.
     */
   def VersionsInfo(lastVersion: Int, linkTo: FPath, defaultAC: ACInfo): VersionsInfo

   /** Note: We don't need to keep the default ACL info as that comes with the message Note: We need
     * to know to keep info on the effective ACL to return the correct default link. That cannot be
     * done by just using the info in the message because we have SolidCmd messages that we can
     * parse and not just Routes, which come with the default container.
     *
     * @param aclActor
     *   the actor for the acl if it exists
     * @param the
     *   last version of the file (so that we can send a pointer back
     * @param linkTo
     *   the linked file (with the content)
     * @param aclActor
     *   the actor for the acl for his resource if it exists
     * @param aclInfo
     *   we keep default actor info here either the default in which case it is a ACContainer or the
     *   local one an ACRef
     */
   trait VersionsInfo(
       lastVersion: Int,
       linkTo: FPath,
       aclInfo: ACInfo
   ):
      import Resource.{AllowHeader, eTag, lastModified}
      import akka.http.scaladsl.model.HttpMethods.*
      import akka.http.scaladsl.model.StatusCodes.{Created, Gone, InternalServerError}
      import run.cosy.http.auth.Agent
      import run.cosy.ldp.SolidCmd
      import run.cosy.ldp.SolidCmd.{Get, Plain, Script}
      import run.cosy.ldp.fs.BasicContainer.AcceptMsg
      var PUTstarted = false

      def vName(v: Int): String = linkName + "." + v

      def versionUrl(v: Int): Uri = uri.sibling(vName(v))

      def versionFPath(v: Int, ext: String): FPath = linkPath.resolveSibling(vName(v) + "." + ext)

      def PrevVersion(v: Int): List[LinkValue] =
        if v > 0 then LinkValue(versionUrl(v - 1), LinkParams.rel("predecessor-version")) :: Nil
        else Nil
      def NextVersion(v: Int): List[LinkValue] =
        if v < lastVersion then
           LinkValue(versionUrl(v + 1), LinkParams.rel("successor-version")) :: Nil
        else Nil

      def LatestVersion(): LinkValue = LinkValue(uri, LinkParams.rel("latest-version"))
      def VersionLinks(v: Int): List[LinkValue] =
        LatestVersion() :: (PrevVersion(v) ::: NextVersion(v))

      def NormalBehavior: Behaviors.Receive[Resource.AcceptMsg] =
         import Resource.*
         import run.cosy.ldp.SolidCmd
         import SolidCmd.{Get, Plain, Wait}
         import akka.http.scaladsl.model.StatusCodes.OK
         import run.cosy.http.auth.Guard

         Behaviors.receiveMessage[Resource.AcceptMsg] { (msg: Resource.AcceptMsg) =>
            context.log.info(s"in NormalBehavior of VersionsInfo($lastVersion, $linkTo) << $msg")
            msg match
             case script: ScriptMsg[?] =>
               import script.given
               script.continue
               Behaviors.same
             case WannaDo(cmdmsg: CmdMessage[?], containerAcl) =>
               // we route the message to the acl actor
               // todo: consider wether it is still needed to do this.
               import run.cosy.http.util.UriX.fileName
               import ACInfo.*
               if (!dotLinkName.isACR) && dotLinkName.hasACR(cmdmsg.target.fileName.get) then
                  // we are not an ACR and the message is going to our ACR, then forward it
                  // todo: the first !dotLinkName.isACR requirement would not exist if this code were not shared.
                  //  should it be shared?
                  aclInfo match
                   case ACtrlRef(_, _, actorRef, _) =>
                     actorRef ! msg
                   case _ =>
                     // todo: we have to check if the message is one to create the ACR. If it is
                     //  (and if it is allowed?) then we need to create the actor, and the resource
                     //  and forward the msg and change the behavior
                     cmdmsg.respondWith(HttpResponse(StatusCodes.NotFound))
               else if containerAcl.container.ancestorOf(aclInfo.container) then
                  Guard.Authorize(cmdmsg, aclInfo, aclUri)
               else Guard.Authorize(cmdmsg, containerAcl, aclUri)
               Behaviors.same
             case Do(cmdmsg @ CmdMessage(cmd, agent, replyTo)) =>
               import cmdmsg.given
               cmd match
                case Plain(req, f) =>
                  req.method match
                   case GET | HEAD =>
                     doPlainGet(cmdmsg, req, f)
                   case OPTIONS =>
                     cmdmsg.respondWith(HttpResponse( // todo, add more
                       NoContent,
                       AllowHeader :: Nil
                     ))
                     Behaviors.same
                   // ACR: Here we can't allow anything in - only RDF graphs, and perhaps only those with specific shapes
                   //   but on the other hand that means the verification is happening at the RDF Graph layer.
                   //   so in a way one level higher up.
                   //   also we do allow PUT on creation (if DELETE is allowed)
                   //   How could we specify this requirement?
                   case PUT =>
                     Put(cmdmsg)
                     Behaviors.same
                   // ACR: would this still be callable? Depends by whome? (cmdmsg contains the info)
                   //  Can make sense if this resource has a default content to return, eg. :imports of parent
                   //  The cleanup operation in implementition here won't be needed
                   case DELETE => Delete(cmdmsg)
                   case m =>
                     cmdmsg.respondWith(HttpResponse(
                       MethodNotAllowed,
                       AllowHeader :: Nil,
                       HttpEntity(s"method $m not supported")
                     ))
                     Behaviors.same
                case Get(url, k) =>
                  // 1. todo: check if we have cached version, if so continue with that
                  // 2. if not, build it from result of plain HTTP request
                  import _root_.run.cosy.RDF.{*, given}
                  import cats.free.Free
                  given sys: ActorSystem[Nothing] = context.system
                  given ec: ExecutionContext      = context.executionContext
                  // todo: we should not have to call resume here as we set everything up for plain
                  SolidCmd.getFromPlain(url, k).resume match
                   case Left(plain) =>
                     context.self.tell(Do(CmdMessage(plain, agent, replyTo)))
                   case _ => ???
                  Behaviors.same
                case Wait(future, u, k) =>
                  // it is difficult to see why a Wait would be sent somewhere,...
                  // but what is sure is that if it is, then this seems the only reasonable thing to do:
                  context.pipeToSelf(future) { tryVal => ScriptMsg(k(tryVal), agent, replyTo) }
                  Behaviors.same
             case pc: PostCreation =>
               // the PostCreation message is sent from Put(...), but it is immediately acted on
               // and so does not come through here. Still if it comes through here it would
               // call this method. Should it come through here?
               BuildContent(pc)
               // todo: cleanup, the PUT above should create a name, and then send the PostCreation message
               Behaviors.same
             case StateSaved(null, cmd) =>
               PUTstarted = false
               // the upload failed, but we can return info about the current state
               val att = Files.readAttributes(linkTo, classOf[BasicFileAttributes])
               cmd.respondWith(HttpResponse(
                 StatusCodes.InternalServerError,
                 Location(uri) :: AllowHeader :: Link(
                   LDPR(Some(uri)) :: (aclLinks(aclInfo) ::: VersionLinks(lastVersion))
                 ) :: headersFor(att),
                 entity = HttpEntity("PUT unsucessful")
               ))
               Behaviors.same
             case StateSaved(fpath, cmd) =>
               import java.nio.file.StandardCopyOption.ATOMIC_MOVE
               val tmpSymLinkPath = Files.createSymbolicLink(
                 linkPath.resolveSibling(linkPath.getFileName.toString + ".tmp"),
                 fpath.getFileName
               )
               Files.move(tmpSymLinkPath, linkPath, ATOMIC_MOVE)
               // todo: state saved should just return the VersionInfo.
               val att = Files.readAttributes(fpath, classOf[BasicFileAttributes])
               cmd.respondWith(HttpResponse(
                 OK,
                 Location(uri) :: AllowHeader :: Link(
                   LDPR(Some(uri)) :: (aclLinks(aclInfo) ::: VersionLinks(lastVersion + 1))
                 ) :: headersFor(att),
                 HttpEntity(`text/plain(UTF-8)`, s"uploaded ${att.size()} bytes")
               ))
               VersionsInfo(lastVersion + 1, fpath, aclInfo).NormalBehavior
         }
      end NormalBehavior

      /** Two ways to count versions both have problems
        *   1. we start the process of building the new resource now, and when finished change the
        *      name. But what happens then if another PUT requests comes in-between?
        *      - If we number each version with an int, we risk overwriting an existing version
        *      - If create Unique names then we don't have a simple mechansims to find previous
        *        versions
        *   1. If we immediately increase the version counter by 1
        *   - then if a PUT fails, we could end up with gaps in our virtual linked history
        *
        * possible answers:
        *   a. we save versions to a directory, where it is easy to get an oversight over all
        *      versions of a resource, and we can use the creation metadata of the FS to work out
        *      what the versions are. We can even use numbering with missing parts, because getting
        *      a full list of the versions and reconstituting the numbers is easy given that they
        *      are confined in a directory.
        *   a. we need to keep a resource (attribute or file) of the versions and their relations
        *   a. we don't allow another PUT before the previous one is finished...
        *
        * The last one is not the most elegant perhaps, but will work. The others options can be
        * explored in other implementations. (Note: Post as Append on LDPRs that accept it adds an
        * extra twist, not taken into account here)
        */
      def Put(cmd: CmdMessage[?]): Unit =
         // todo: fix the type of the arguments so that this cannot fail
         val CmdMessage(run.cosy.ldp.SolidCmd.Plain(req, _), _, _) = cmd: @unchecked
         // 1. we filter out all unaceptable requests, and return error responses to those
         if PUTstarted then
            cmd.respondWith(HttpResponse(
              StatusCodes.Conflict,
              Seq(),
              HttpEntity("PUT already ongoing. Please try later")
            ))
            return ()

         import headers.{EntityTag, `If-Match`, `If-Unmodified-Since`}
         val imh      = req.headers[`If-Match`]
         val ium      = req.headers[`If-Unmodified-Since`]
         lazy val att = Files.readAttributes(linkTo, classOf[BasicFileAttributes])

         if imh.isEmpty && ium.isEmpty then
            cmd.respondWith(HttpResponse(
              StatusCodes.PreconditionRequired,
              Location(uri) :: AllowHeader :: Link(
                LDPR() :: { aclLinks(aclInfo) ::: VersionLinks(lastVersion) }
              ) :: headersFor(att),
              HttpEntity(
                "LDP Servers requires valid `If-Match` or `If-Unmodified-Since` headers for a PUT"
              )
            ))
            return ()

         val precond1 = imh.exists { case `If-Match`(range) =>
           val et = eTag(att)
           EntityTag.matchesRange(et.etag, range, false)
         }
         lazy val precond2 =
           req.headers[`If-Unmodified-Since`].map { case `If-Unmodified-Since`(dt) =>
             dt.clicks >= att.lastModifiedTime().toMillis
           }
         if !(precond1 || (precond2.size > 0 && precond2.forall(_ == true))) then
            cmd.respondWith(HttpResponse(
              StatusCodes.PreconditionFailed,
              Location(uri) :: AllowHeader :: Link(
                LDPR() :: { aclLinks(aclInfo) ::: VersionLinks(lastVersion) }
              ) :: headersFor(att),
              HttpEntity(
                "LDP Servers requires valid `If-Match` or `If-Unmodified-Since` headers for a PUT"
              )
            ))
            return ()

         saveContent(cmd, req)

      protected def saveContent(cmd: CmdMessage[?], req: HttpRequest): Unit =
        // 2. We save data to storage, which if successful will lead to a new version message to be sent
        // todo: we need to check that the extension fits the previous mime types
        //     this is definitively clumsy.
        dotLinkName.remaining(linkTo.getFileName.toString) match
         case Some(List(AsInt(num), ext))
             if req.entity.contentType.mediaType.fileExtensions.contains(ext) =>
           val linkToPath: FPath = versionFPath(lastVersion + 1, ext)
           PUTstarted = true
           BuildContent(PostCreation(linkToPath, cmd))
         // todo: here we should change the behavior to one where requests are stashed until the upload
         // is finished, or where they are returned with a "please wait" response...
         case _ => cmd.respondWith(HttpResponse(
             StatusCodes.Conflict,
             Seq(),
             HttpEntity("At present we can only accept a PUT with the same " +
               "Media Type as previously sent. Which is " + extension(linkTo))
           ))

      private def GetVersionResponse(path: FPath, ver: Int, mt: MediaType): HttpResponse =
         context.log.info(s"GetVersionResponse( $path, $ver, $mt)")
         try
            // todo: Akka has a MediaTypeNegotiator
            val attr = Files.readAttributes(path, classOf[BasicFileAttributes])
            HttpResponse(
              StatusCodes.OK,
              lastModified(attr) :: eTag(attr) :: AllowHeader :: Link(
                LDPR() :: { aclLinks(aclInfo) ::: VersionLinks(ver) }
              ) :: Nil,
              HttpEntity.Default(
                akka.http.scaladsl.model.ContentType(mt, () => HttpCharsets.`UTF-8`),
                path.toFile.length(),
                FileIO.fromPath(path)
              )
            )
         catch
            case e: Exception => HttpResponse(
                StatusCodes.NotFound,
                entity = HttpEntity(
                  `text/html(UTF-8)`,
                  s"could not find attributes for content: " + e.toString
                )
              )

      def doPlainGet[A](
          cmdmsg: CmdMessage[A],
          req: HttpRequest,
          f: HttpResponse => SolidCmd.Script[A]
      ): Behavior[Resource.AcceptMsg] =
         cmdmsg.continue(f(plainGet(req)))
         Behaviors.same

      def plainGet(req: HttpRequest): HttpResponse =
         val mt: MediaType          = mediaType(linkTo)
         val accMR: Seq[MediaRange] = req.headers[Accept].flatMap(_.mediaRanges)
         context.log.info(s"PlainGet. accMR=$accMR")
         if accMR.exists(_.matches(mt)) then
            import run.cosy.http.util.UriX.fileName
            dotLinkName.remaining(req.uri.fileName.get) match
             case Some(List(AsInt(num), ext)) if mt.fileExtensions.contains(ext) =>
               GetVersionResponse(linkPath.resolveSibling(dotLinkName.baseName), num, mt)
             case Some(List(AsInt(num))) if num >= 0 && num <= lastVersion =>
               // the request is for a version, but mime left open
               GetVersionResponse(
                 linkPath.resolveSibling(dotLinkName.Version(num, mt.fileExtensions.head).name),
                 num,
                 mt
               )
             case Some(List()) => GetVersionResponse(linkTo, lastVersion, mt)
             case _ => HttpResponse(
                 StatusCodes.NotFound,
                 entity =
                   HttpEntity(s"could not find resource for <${req.uri}> with " + req.headers)
               )
         else
            import akka.http.scaladsl.model.ContentTypes.`text/html(UTF-8)`
            HttpResponse(
              StatusCodes.UnsupportedMediaType,
              entity = HttpEntity(
                `text/html(UTF-8)`,
                s"requested content Types where $accMR but we only have $mt"
              )
            )
      end plainGet

      /** Specified in:
        *   - [[https://tools.ietf.org/html/rfc7231#section-4.3.5 4.3.5 DELETE]] of HTTP 1.1 RFC
        *     7231
        *   - [[https://www.w3.org/TR/ldp/#ldpc-HTTP_DELETE 5.2.5 HTTP DELETE]] of LDP spec
        *   - [[https://solidproject.org/TR/protocol#deleting-resources in 5.4 Deleting Resources]]
        *     of Solid Protocol
        *
        * Creates a "file.archive" directory, moves the link into it and points the link to the
        * archive. Then it can return a response. And the done behavior is started after cleanup.
        *
        * Should also delete Auxilliary resources (which at present just include the linked-to
        * document) When done the actor must shutdown and send a deleted notification to the parent,
        * so that it can remove the link from its cache.
        *
        * what should one do if a delete fails on some of the resources? Best to create an archive
        * directory and move all files there, so that they are no longer visible, and can be cleaned
        * away later.
        *
        * @param req
        *   the DELETE Request
        * @param replyTo
        * @return
        */
      // ACR: not needed as cleanup is done by actor that created this resource?
      //  but it could be a way to just state: go back to original default `:imports` statement
      //  also: we would not create a special archive directory for this single resource
      def Delete(cmd: CmdMessage[?]): Behavior[Resource.AcceptMsg] =
         // todo: fix the type of the arguments so that this cannot fail
         val CmdMessage(run.cosy.ldp.SolidCmd.Plain(req, _), _, _) = cmd: @unchecked
         import akka.http.scaladsl.model.StatusCodes.{Conflict, NoContent}

         import java.io.IOException
         import java.nio.file.LinkOption.NOFOLLOW_LINKS
         import java.nio.file.attribute.BasicFileAttributes
         import java.nio.file.{
           AtomicMoveNotSupportedException,
           DirectoryNotEmptyException,
           FileAlreadyExistsException,
           NoSuchFileException,
           NotLinkException,
           StandardCopyOption
         }

         val javaMime = `text/x-java-source`.withCharset(`UTF-8`)
         val linkTo: FPath =
           try
              val lp = Files.readSymbolicLink(linkPath)
              linkPath.resolveSibling(lp)
           catch
              case e: UnsupportedOperationException =>
                log.error("Operating Systems does not support links.", e)
                cmd.respondWith(HttpResponse(
                  InternalServerError,
                  entity = HttpEntity(`text/plain(UTF-8)`, "Server error")
                ))
                // todo: one could avoid closing the whole system and instead shut down the root solid actor (as other
                // parts of the actor system could continue working
                context.system.terminate()
                return Behaviors.stopped
              case e: NotLinkException =>
                log.warn(s"type of <$linkPath> changed since actor started", e)
                cmd.respondWith(HttpResponse(
                  Conflict,
                  entity = HttpEntity(`text/plain(UTF-8)`, "Resource seems to have changed type")
                ))
                return Behaviors.stopped
              case e: SecurityException =>
                log.error(s"Could not read symbolic link <$linkPath> on DELETE request", e)
                // todo: returning an InternalServer error as this is not expected behavior
                cmd.respondWith(HttpResponse(
                  InternalServerError,
                  entity = HttpEntity(
                    `text/plain(UTF-8)`,
                    "There was a problem deleting resource on server. Contact Admin."
                  )
                ))
                return Behaviors.stopped
              case e: IOException =>
                log.error(
                  s"Could not read symbolic link <$linkPath> on DELETE request. " +
                    s"Will continue delete attempt",
                  e
                )
                linkPath
         val archive =
           try
              Files.createDirectory(archiveDir)
           catch
              case e: UnsupportedOperationException =>
                log.error(
                  "Operating Systems does not support creation of directory with no(!) attributes!" +
                    " This should never happen",
                  e
                )
                cmd.respondWith(HttpResponse(
                  InternalServerError,
                  entity = HttpEntity(`text/plain(UTF-8)`, "Server error")
                ))
                // todo: one could avoid closing the whole system and instead shut down the root solid actor (as other
                // parts of the actor system could continue working
                context.system.terminate()
                return Behaviors.stopped
              case e: FileAlreadyExistsException =>
                log.warn(
                  s"Archive directory <$archiveDir> allready exists. " +
                    s"This should not have happened. Logic error in program suspected. " +
                    s"Or user created archive dir by hand. " +
                    s"Will continue by trying to save to existing archive. Things could go wrong.",
                  e
                )
                archiveDir
              case e: SecurityException =>
                log.error(
                  s"Exception trying to create archive directory. " +
                    s"JVM is potentially badly configured. ",
                  e
                )
                cmd.respondWith(HttpResponse(
                  InternalServerError,
                  entity =
                    HttpEntity(`text/plain(UTF-8)`, "Server error. Potentially badly configured.")
                ))
                return Behaviors.stopped
              case e: IOException =>
                log.error(s"Could not create archive dir <$archiveDir> for DELETE", e)
                cmd.respondWith(HttpResponse(
                  InternalServerError,
                  entity = HttpEntity(`text/plain(UTF-8)`, "could not delete, contact admin")
                ))
                return Behaviors.stopped

         try // move link to archive. At the minimum this will result in a delete
            Files.move(linkPath, archive.resolve(linkPath.getFileName))
         catch
            case e: UnsupportedOperationException => // we don't even have a symlink. Problem!
              log.error(
                s"tried move <$linkPath> to <$archive>. " +
                  s"But OS does not support an attribute?. JVM badly configured. ",
                e
              )
              cmd.respondWith(HttpResponse(
                InternalServerError,
                entity =
                  HttpEntity(`text/plain(UTF-8)`, "Server badly configured. Contact admin.")
              ))
              return Behaviors.stopped
            case e: DirectoryNotEmptyException =>
              log.error(
                s"tried move link <$linkPath> to <$archive>. " +
                  s" But it turns out the moved object is a non empty directory!",
                e
              )
              cmd.respondWith(HttpResponse(
                Conflict,
                entity =
                  HttpEntity(`text/plain(UTF-8)`, "Coherence problem with resources. Try again.")
              ))
              return Behaviors.stopped
            case e: AtomicMoveNotSupportedException =>
              log.error(
                s"Message should not appear as Atomic Move was not requested. Corrupt JVM?",
                e
              )
              cmd.respondWith(HttpResponse(
                InternalServerError,
                entity =
                  HttpEntity(`text/plain(UTF-8)`, "Server programming or installation error")
              ))
              return Behaviors.stopped
            case e: SecurityException =>
              log.error(s"Security Exception trying move link <$linkPath> to archive.", e)
              cmd.respondWith(HttpResponse(
                InternalServerError,
                entity =
                  HttpEntity(`text/plain(UTF-8)`, "Server programming or installation error")
              ))
              return Behaviors.stopped
            case e: IOException =>
              log.error(s"IOException trying move link <$linkPath> to archive.", e)
              cmd.respondWith(HttpResponse(
                InternalServerError,
                entity =
                  HttpEntity(`text/plain(UTF-8)`, "Server programming or installation error")
              ))
              return Behaviors.stopped
            case e: FileAlreadyExistsException =>
              log.warn(
                s"trying to move <$linkPath> to <$archive> but a file with that name already exists. " +
                  s"Will continue with DELETE operation."
              )
              try
                 Files.delete(linkPath)
              catch
                 case e: NoSuchFileException =>
                   log.warn(
                     s"Trying to delete <$linkPath> link but it no longer exists. Will continue DELETE operation"
                   )
                 case e: DirectoryNotEmptyException =>
                   log.warn(
                     s"Attempting to delete <$linkPath> link but it suddenly " +
                       s" seems to have turned into a non empty directory! ",
                     e
                   )
                   cmd.respondWith(HttpResponse(
                     Conflict,
                     entity = HttpEntity(
                       `text/plain(UTF-8)`,
                       "Coherence problem with resources. Try again."
                     )
                   ))
                   return Behaviors.stopped
                 case e: SecurityException =>
                   log.error(
                     s"trying to delete <$linkPath> caused a security exception. " +
                       s"JVM rights not properly configured.",
                     e
                   )
                   cmd.respondWith(HttpResponse(
                     InternalServerError,
                     entity =
                       HttpEntity(`text/plain(UTF-8)`, "Server badly configured. Contact admin.")
                   ))
                   return Behaviors.stopped
                 case e: IOException =>
                   log.error(
                     s"trying to delete <$linkPath> link cause IO Error." +
                       s"File System problem.",
                     e
                   )
                   cmd.respondWith(HttpResponse(
                     InternalServerError,
                     entity = HttpEntity(`text/plain(UTF-8)`, "Problem on server. Contact admin.")
                   ))
                   return Behaviors.stopped
         try
            Files.createSymbolicLink(linkPath, linkPath.getFileName)
         catch
            case e =>
              log.warn(
                s"could not create a self link <$linkPath> -> <$linkPath>. " +
                  s"Could lead to problems!",
                e
              )

         // if we got this far we got the main work done. So we can always already return a success
         cmd.respondWith(HttpResponse(NoContent))

         // todo: here we should actually search the file system for variants
         //  this could also be done by a cleanup actor
         // Things that should not be cleaned up:
         //   - <file.count> as that gives the count for the next version.
         //   - other ?
         //  This indicates that it would be good to have a link to the archive
         val variants = List() // we don't do variants yet
         val cleanup: List[FPath] =
           if linkPath == linkTo then variants.toList else linkTo :: variants.toList
         cleanup.foreach { p =>
           try
              Files.move(p, archive.resolve(p.getFileName))
           catch
              case e: UnsupportedOperationException => // we don't even have a symlink. Problem!
                log.error(
                  s"tried move <$p> to <$archive>. " +
                    s"But OS does not support some attribute? JVM badly configured. ",
                  e
                )
              case e: FileAlreadyExistsException =>
                log.warn(
                  s"trying to move <$p> to <$archive> but a file with that name already exists. " +
                    s"Will continue with DELETE operation."
                )
              case e: DirectoryNotEmptyException =>
                log.error(
                  s"tried move link <$p> to <$archive>. " +
                    s" But it turns out the moved object is a non empty directory!",
                  e
                )
              case e: AtomicMoveNotSupportedException =>
                log.error(
                  s"Tried moving <$p> to <$archive>. " +
                    s"Message should not appear as Atomic Move was not requested. Corrupt JVM?",
                  e
                )
              case e: SecurityException =>
                log.error(s"Security Exception trying move link <$p> to archive.", e)
              case e: IOException =>
                log.error(s"IOException trying move link <$p> to archive.", e)
         }
         GoneBehavior
      end Delete

      /** Directory where an archive is stored before deletion */
      def archiveDir: FPath = linkPath.resolveSibling(linkPath.getFileName.toString + ".archive")
