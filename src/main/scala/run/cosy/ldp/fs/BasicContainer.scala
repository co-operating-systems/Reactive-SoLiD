/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp.fs

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, PostStop, PreRestart}
import akka.http.scaladsl.model
import akka.http.scaladsl.model.StatusCodes.{
  Conflict,
  Created,
  Gone,
  InternalServerError,
  MovedPermanently,
  NoContent,
  NotFound,
  NotImplemented,
  OK,
  PermanentRedirect,
  UnsupportedMediaType
}
import akka.http.scaladsl.model.headers.{
  Accept,
  Allow,
  HttpChallenge,
  Link,
  LinkParam,
  LinkParams,
  LinkValue,
  RawHeader,
  ResponseHeader,
  `Content-Type`,
  `WWW-Authenticate`
}
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.http.scaladsl.server.ContentNegotiator.Alternative
import akka.http.scaladsl.server.ContentNegotiator.Alternative.ContentType
import akka.http.scaladsl.server.{RequestContext, RouteResult}
import akka.stream.scaladsl.{Concat, FileIO, Merge, RunnableGraph, Source}
import akka.stream.{ActorMaterializer, IOResult, Materializer}
import akka.util.ByteString
import akka.{Done, NotUsed}
import run.cosy.http.headers.Slug
import run.cosy.http.{IResponse, RDFMediaTypes, RdfParser}
import run.cosy.http.auth.{Agent, Anonymous, Guard, KeyIdAgent, WebServerAgent}
import run.cosy.http.RDFMediaTypes.`text/turtle`
import run.cosy.ldp.fs.BasicContainer
import run.cosy.ldp.fs.BasicContainer.{AllowHeader, LinkHeaders, `Accept-Post`, rdfContentTypes}
import run.cosy.ldp.{ResourceRegistry, SolidCmd}

import java.io.{BufferedReader, BufferedWriter, FileReader, FileWriter}
import java.lang.UnsupportedOperationException
import java.nio.file.attribute.UserDefinedFileAttributeView
import java.nio.file.{FileAlreadyExistsException, FileSystem, FileVisitOption, Files, Path}
import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalDateTime, ZoneId, ZoneOffset}
import java.util.{Locale, stream}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try, Using}
import akka.event.slf4j.Slf4jLogger
import akka.http.scaladsl.model.HttpMethods.*
import scalaz.NonEmptyList.nel
import scalaz.{ICons, INil, NonEmptyList}

import scala.collection.immutable.HashMap
import scala.util.Random
import _root_.run.cosy.http.util.UriX.*

/** An LDP/Solid Container corresponding to a directory on the FileSystem.
  *
  * Because we are typing the actor, we need to check before creation what the type of the actor
  * created is!! The main difference between a directory and a file is that a directory can route
  * messages to subdirectores (hence `Route`` and `Do`). The directory is in a good position to know
  * its contents.
  *
  * If the next message is a `Route` then depending on the action
  *   - PUT: must create the container
  *   - GET, POST, DELETE: must be sent to another container
  *
  * If the next message is a `Do` then it needs to check * If the URL ends in `/` the next actor
  * must be mapped to a directory * Otherwise the container needs to look at the mime type and
  * calculate the resource e.g. `cat` with mime type `image/jpg` goes to `cat.jpg` e.g. `cat.jpg`
  * with mimte type `image/jpg` got to `cat.jpg` e.g. `cat.exe` with mime type `image/jpg` goes to
  * `cat.exe$.jpg` * POST must create a new name for the resource if it already exists and forward
  * that to the actor * GET, PUT, ... can create the resource actor which will then copy the content
  * to disk or serve it
  *
  * Note: because actors have unique names, the container only needs to create the actor and forward
  * the message. It does not need to block on saving to disk, or creating the file.
  *
  * If we did not type the actor then the container could for GET just create the actor, which could
  * check its existence.
  *
  * Note, the container may need to read some config files on startup, so it will need to check its
  * own existence in any case.
  *
  * It is the Container's role to
  *   - name new resources on a POST, and check with the Guard they can be created
  *   - on a PUT with intermediary dirs it can create the subactor, which will create the resource,
  *     this works because actors have unique names. But the container needs to check with its Guard
  *     before doing that.
  *   - on DELETE it needs to check with the container and the actor
  *   - route messages for GET onto subactors (which have a guard?)
  *   - for DIRS these get passed along
  *   - for files these get passed along
  *   - PATCH is like PUT
  *     - QUERY is like GET
  */
object BasicContainer:

   import akka.http.scaladsl.model.HttpHeader
   import akka.http.scaladsl.model.HttpHeader.ParsingResult.Ok

   import java.nio.file.{FileTreeIterator, FileVisitOption}
   import java.time.{Clock, Instant}
   import java.util.{Spliterator, Spliterators}
   import java.util.stream.{Stream, StreamSupport}

   import org.w3.banana.*
   import _root_.run.cosy.RDF.{given, *}
   import _root_.run.cosy.RDF.ops.{given, *}
   import _root_.run.cosy.RDF.Prefix.ldp
   val ldpBC = ldp.BasicContainer.toAkka
   val ldpr  = ldp.Resource.toAkka

   /** A collection of "unwise" characters according to
     * [[https://tools.ietf.org/html/rfc2396#section-2.4.3 RFC 2396]].
     */
   val UnwiseChars = """{}|\^[]`"""

   /** A collection of "invalid" characters according to
     * [[https://tools.ietf.org/html/rfc2396#section-2.4.3 RFC 2396]].
     */
   val Delims = """"<>#%""""

   /** Generic delimiters according to [[https://tools.ietf.org/html/rfc3986#section-2.2 RFC 3986]]
     */
   val GenDelims           = """:/?#[]@"""
   val SubDelims           = """!$&'()*+,;="""
   val ReactiveSolidDelims = "_"
   val Remove = (UnwiseChars + Delims + GenDelims + SubDelims + ReactiveSolidDelims).toSet

   val MaxFileName = 100

   given clock: Clock = Clock.systemDefaultZone

   def santiseSlug(slugTxt: String): String =
      val santized =
        slugTxt.takeWhile(_ != '.').filterNot(c => Remove.contains(c) || c.isWhitespace)
      santized.substring(0, Math.min(MaxFileName, santized.size))

   val timeFormat = DateTimeFormatter.ofPattern("yyyyMMdd-N").withZone(ZoneId.of("UTC"))

   def createTimeStampFileName(using clock: Clock): String =
     timeFormat.format(LocalDateTime.now(clock))

   // todo: add Content-Encoding, Content-Language
   def linkToName(linkName: String, cts: ContentType) =
     linkName + ".0." + cts.contentType.mediaType.fileExtensions.headOption.getOrElse("bin")

   /** @return (linkName, linkTo) strings */
   def createLinkNames(req: HttpRequest)(using clock: Clock): (String, String) =
      val linkName = createNewResourceName(req)
      val linkTo   = linkToName(linkName, req.entity.contentType)
      (linkName, linkTo)

   def createNewResourceName(req: HttpRequest)(using clock: Clock): String =
     req.headers.collectFirst { case Slug(name) => name }
       .map(slug => santiseSlug(slug.toString))
       .getOrElse(createTimeStampFileName)

//	import java.nio.file.{FileTreeWalker,FileVisitOption}
//	def ls(start: Path, options: FileVisitOption*): Source[FileTreeWalker.Event, NotUsed] =
//		val iterator = new FileTreeIterator(start, 1, options)
//		val factory = () => try {
//			val spliterator = Spliterators.spliteratorUnknownSize(iterator, Spliterator.DISTINCT)
//			StreamSupport.stream(spliterator, false).onClose(iterator.close)
//		} catch {
//			case e@(_: (Error | RuntimeException)) =>
//				iterator.close()
//				throw e
//		}
//		import akka.stream.scaladsl.StreamConverters
//		//todo: factory throws an Exception, where is that caught?
//		StreamConverters.fromJavaStream(factory)
//	end ls
   import akka.http.scaladsl.model.headers.LinkParams.rel
   import akka.http.scaladsl.model.MediaRanges.`*/*`
   import RDFMediaTypes.`text/turtle`

   // get all the  URIs with link rel="type"
   def filterLDPTypeLinks(links: Seq[Link]): Seq[Uri] =
     links.flatMap { link =>
       link.values.collect {
         case LinkValue(uri, params)
             if params.collectFirst({ case rel("type") => true }).isDefined => uri
       }
     }

   val LinkHeaders = Link(
     LinkValue(ldp.BasicContainer.toAkka, rel("type")),
     LinkValue(ldp.Resource.toAkka, rel("type"))
   )

   val AllowHeader =
      import HttpMethods.*
      Allow(GET, POST, DELETE, HEAD, OPTIONS)

   val `Accept-Post` =
     RawHeader("Accept-Post", `text/turtle`.value + ", " + `*/*`.value)

   val rdfContentTypes = Seq(`text/turtle`)
   // log.info(s"created LDPC($ldpcUri,$root)")

   val ttlPrefix: Source[String, NotUsed] = Source(
     List(
       "@prefix stat: <http://www.w3.org/ns/posix/stat#> .\n",
       "@prefix ldp: <http://www.w3.org/ns/ldp#> .\n\n",
       "<> a ldp:BasicContainer.\n\n"
     )
   )

   import run.cosy.ldp.Messages.*

   type AcceptMsg = Cmd | PostCreation

   def apply(
       containerUrl: Uri,
       dir: Path
   )(using reg: ResourceRegistry): Behavior[AcceptMsg] =
     Behaviors.setup[AcceptMsg] { (context: ActorContext[AcceptMsg]) =>
        // val exists = Files.exists(root)
        reg.addActorRef(containerUrl.path, context.self)
        context.log.info(
          s"started actor for <${containerUrl}> in dir <file:/${dir.toAbsolutePath}>"
        )
        new BasicContainer(containerUrl, dir, new Spawner(context)).behavior
     }

   /** The resource with `name` has been created, but the request `cmd` must still be completed.
     * E.g. the data from the body of `cmd` must still be saved into the new resource. (could one
     * forward the old request? That would just mean downstream would need to be very careful to
     * compare the object of `Do` to notice that the request was no longer aimed there, and so
     * interpret the content different... A little easy to make mistakes) Todo: can the cmd be
     * anything else than a Plain HTTP one? (ie should one narrow the type?)
     */
   case class PostCreation(
       name: Path,
       cmd: CmdMessage[?]
   )

/** Class of Resource objects, mapped to a path on the file system. Our convetion for files on the
  * FS at present (later to be modularized) is designed to avoid needing to load the container
  * contents into memory, and to allow content negotiation to work both locally and on the file
  * system. These are the rules:
  *   1. The root of the file is the string up to the first "." (same with directories)
  *   1. All roots have a symbolic link to the original resource. So if `cat.raw` is a large picture
  *      loaded first then `cat` is a symbolic link to `cat.raw`. Later the resource actor can keep
  *      copies of other versions for other devices such as smart phones, but link will keep track
  *      of which is the original file.
  *   1. Requests for `cat.jpg` are passed to the `cat` child actor that can deal with content
  *      negotiation for that resource
  *   1. Languagee based content negotiation (not implemented) would on a request for `blog` search
  *      for `blog.en.html`
  *   1. The file pointed to by the symlink will contain metadata (perhaps extended attributes)
  *      informing the server of other versions.
  *   1. The directory will contain xattrs of its own:
  *      - one of these will be set after the actor has gone through the directory to set all the
  *        symlinks, so it knows that it need not do it again
  *      - others?
  *   1. To be able to deal with Multiple Posts of content with the same Slug (Eg. If a POST with
  *      `Slug: My Blog` is posted 3 times) the container needs to know what the next version is
  *      without having to download the whole dir. We can add metadata for a symlink so that the
  *      container can know that the next name should be MyBlog_4, without needing to search for
  *      MyBlog_1`, `MyBlog_2` and `MyBlog_3` This avoids having to create ugly hash based file
  *      names.
  *   1. alternatively instead of using xattrs every root could come with a `x.meta.ttl` that
  *      contains the file meta-data
  *   1. If a resource `cat` is deleted the actor will create a `cat.archive` directory and move all
  *      associatd files there. (this is to be able to recover from a problem half way through
  *      deleting.) The `cat` link is removed and a new one created to the archive directory. This
  *      will allow us to enforce a memory that a file is no longer available. Better methods of
  *      doing this could be possible.
  *
  * Some Advantages:
  *
  *   - The symbolic link trick allows Content Negotiation to work correctly when files are edited
  *     on the FS direectly, since links with relative URLs in one document to the root name of a
  *     file, can be viewed locally as if they were Negotiated by local editors, since they can find
  *     the file by following the symbolic link.
  *   - It also helps a lot for RDF formats: If the first version uploaded is `card.ttl` then `card`
  *     will link to `card.ttl` and that file can be the one updated on PATCH or PUT.
  *   - We want to make Content Negotiation work right out of the box, so as to avoid changes in
  *     format preferences (such as a move from ttl to json-ld or vice versa, breaking all the
  *     links)
  *   - There are important speed advantage to be had, by using xattrs as that avoids walking the
  *     whole directory
  *     - an actor only needs to check if the root of the file (e.g. `blog`) exists, which is one
  *       call to the FS. If the file exists, then the Container can start the child actor, and
  *       later pass on calls to it
  *     - The child actor can retrieve the Xattrs from the original file, to find out where the
  *       variants are. Otherwise the Containere would need to load the whole directory contents in
  *       memory, which could be slow if too many files are listed there, and the result would uses
  *       up a lot of memory. (this may be negligeable for a few directores, but could become
  *       problematic for a large number of open ones).
  *
  * Notes:
  *   - the actor could load the full dir data into memory on a container that is heavily used when
  *     needed.
  *   - ideally the attributes should be mapped to the files, so that it should be possible to
  *     recover them by running through the files
  *
  * @param containerUrl
  *   the URL of the container. (without the slash, so that it is east to write `ctnr / path`)
  * @param dirPath
  *   the path on the filesystem that corresponds to the container
  */
class BasicContainer private (
    containerUrl: Uri,
    dirPath: Path,
    context: Spawner
)(using reg: ResourceRegistry):
   import akka.http.scaladsl.model.HttpCharsets.`UTF-8`
   import akka.http.scaladsl.model.MediaTypes.{`text/plain`, `text/x-java-source`}
   import akka.http.scaladsl.model.headers.{`Content-Type`, Link, LinkParams, LinkValue, Location}
   import akka.http.scaladsl.model.{ContentTypes, HttpCharsets, HttpEntity, HttpMethods}
   import BasicContainer.*
   import run.cosy.ldp.Messages.*
   import run.cosy.ldp.SolidCmd.{Get, Plain, Script, Wait}
   import run.cosy.ldp.fs.{CRef, Ref, RRef}
   import run.cosy.ldp.fs.APath
   import run.cosy.ldp.fs.Attributes.{Archived, Other, OtherAtt}

   import java.nio.file.attribute.BasicFileAttributes
   import java.time.Instant
   // The types of references to data about respources associated with a path
   // `Ref`s have already launched an Actor
   type Refs     = Ref | ActorPath
   type Contents = HashMap[String, Refs]
   type Counter  = Map[String, Int]
   // one could add a behavior for if it exists but is not a container

   // we don't need this yet, as we are not shutting down the Container Actor yet.
   //			  .receiveSignal {
   //				case (ctx, PostStop) =>
   //					registry.removePath(ldpcUri.path)
   //					Behaviors.stopped
   //			}

   def behavior: Behavior[AcceptMsg] =
      val isDir = Files.isDirectory(dirPath)
      if isDir then new Dir().start
      else ??? // if PUT create dir else return error

   // url for resource with `name` in this container
   def urlFor(name: String): Uri = containerUrl.withPath(containerUrl.path / name)
   lazy val aclUri: Uri          = containerUrl.withPath(containerUrl.path ?/ ".acl")
   lazy val aclHeader            = Link(aclUri, LinkParams.rel("acl"))
//	lazy val dotLinkName: DotFileName = DotFileName(linkName)

   /** Return a Source for reading the relevant files for this directory. Note: all symbolic links
     * and dirs are our resources, so long as they don't have a `.` in them.
     *
     * todo: now that we have symlinks to archives, we would need to test every symlink for what it
     * links to! So we should perhaps instead use a plain file for deleted resources!
     */
   val dirList: Source[(Path, BasicFileAttributes), NotUsed] = Source.fromGraph(
     DirectoryList(dirPath, 1, FileVisitOption.FOLLOW_LINKS) { isLDPPath }
   )

   def dirIsEmpty: Boolean =
      import scala.jdk.FunctionConverters.given
      Files.find(dirPath, 1, isLDPPath.asJava).findAny().isEmpty

   /** contained resources have no `.` in them, and don't point to symbolic links. We assume here
     * that we have `FileVisitOption.FOLLOW_LINKS` set, as we want to know the size and dates of the
     * resulting files, not of the link.
     */
   private def isLDPPath(path: Path, withAtt: BasicFileAttributes): Boolean =
     !(path == dirPath || withAtt.isSymbolicLink || path.getFileName.toString.contains('.'))

   def containsAsTurtle(path: Path, att: BasicFileAttributes): String =
      val filename = path.getFileName.toString + { if att.isDirectory then "/" else "" }
      s"""<> ldp:contains <$filename> .
         |    <$filename> stat:size ${att.size};
         |        stat:mtime ${att.lastModifiedTime().toMillis};
         |        stat:ctime ${att.creationTime().toMillis} .
         |""".stripMargin

//	def notContainerBehavior(exists: Boolean): Behaviors.Receive[Cmd] =
//		val exists = Files.exists(dirPath)
//		Behaviors.receiveMessage[Cmd] { msg =>
//			msg match
//				case Route(path, HttpRequest(PUT, _, _, _, _), replyTo) =>
//					replyTo ! HttpResponse(MethodNotAllowed, entity = s"PUT not implemented")
//				case Route(_, req,replyTo) => replyTo ! HttpResponse(NotFound, entity = s"could not find ${req.uri}")
//				case Do(req,replyTo) => replyTo ! HttpResponse(MethodNotAllowed, entity = s"Not implemented")
//			Behaviors.same
//		}

   // the dir behavior caches info about the ldp:contains relations and about counters for files
   // to avoid requests to the FS
   class Dir(contains: Contents = HashMap(), counters: Counter = HashMap()):

      import akka.stream.alpakka.file.scaladsl.Directory

      import java.nio.file.attribute.BasicFileAttributes
      import java.time.Instant
      import scala.annotation.tailrec

      /** Create ACL file. When should this be done? Certainly on creation. But what if there is no
        * ACL on actor startup? What should be put in the ACL?
        */
//		def createACL(defaults: Rdf#Graph): Boolean = true

      lazy val start: Behavior[AcceptMsg] =
        Behaviors.receiveMessage[AcceptMsg] { (msg: AcceptMsg) =>
           context.log.info(s"received in ${containerUrl.path} message $msg")
           import BasicContainer.PostCreation
           msg match
            case wd: WannaDo =>
              given ac: ActorContext[ScriptMsg[?] | Do] =
                context.context.asInstanceOf[ActorContext[ScriptMsg[?] | Do]]
              Guard.Authorize(wd.msg, aclUri)
              Behaviors.same
            case script: ScriptMsg[?] =>
              import script.given
              script.continue
              Behaviors.same
            case Do(cmdmsg @ CmdMessage(cmd, agent, replyTo)) =>
              context.log.info(s"received $cmd from $agent")
              import cmdmsg.given
              cmd match
               case p @ Plain(_, _) =>
                 run(p, agent, replyTo)
               case Get(url, k) =>
                 // 1. todo: check if we have cached version, if so continue with that
                 // 2. if not, build it from result of plain HTTP request
                 import cats.free.Free
                 import _root_.run.cosy.RDF.{given, *}
                 // todo: we should not have to call resume here as we set everything up for plain
                 SolidCmd.getFromPlain(url, k).resume match
                  case Left(plain @ Plain(req, k)) => run(plain, agent, replyTo)
                  case _                           => ???
               case Wait(future, u, k) =>
                 // it is difficult to see why a Wait would be sent somewhere,...
                 // but what is sure is that if it is, then this seems the only reasonable thing to do:
                 context.context.pipeToSelf(future) { tryVal =>
                   ScriptMsg(k(tryVal), agent, replyTo)
                 }
                 Behaviors.same
            case create: PostCreation =>
              // place to do things on container creation
              // should ACL rules be set up here, or on startup? What else can be done on creation?
//						createACL(null)
              // reply that done
              create.cmd.respondWith(HttpResponse(
                Created,
                Location(
                  containerUrl.withPath(containerUrl.path / "")
                ) :: LinkHeaders :: AllowHeader :: Nil
              ))
              Behaviors.same
            case routeMsg: RouteMsg => routeHttpReq(routeMsg)
            case ChildTerminated(name) =>
              reg.removePath(containerUrl.path / name)
              new Dir(contains - name, counters).start
        }.receiveSignal {
          case (_, signal) if signal == PreRestart || signal == PostStop =>
            counters.foreach { (name, int) =>
              saveSiblingsCountFor(name, int)
            }
            Behaviors.same
        }

      /** Get Ref for name by checking in cache, and if not on the FS. It does not create an actor
        * for the Ref. (An improved version could decide not to check the FS if a full upload of the
        * FS properties have just occurred.)
        *
        * @prefix
        *   name the content negotiated name of the resource and actor relative to this Dir
        * @prefix
        *   createActorIfNeeded if the resource has not actor associated with it, created it first
        * @return
        *   the Ref and the new Dir state, or None of there is no Reference at that location.
        */
      def getRef(name: String): Option[(Ref, Dir)] =
         context.log.info(s"in <$dirPath>.getRef($name)")
         contains.get(name).map { (v: Refs) =>
           v match
            case ref: Ref => (ref, Dir.this)
            case fa: ActorPath =>
              val r = context.spawn(fa, urlFor(name))
              (r, new Dir(contains + (name -> r), counters))
         }.orElse {
           import java.io.IOException
           import java.nio.file.LinkOption.NOFOLLOW_LINKS
           import java.nio.file.attribute.BasicFileAttributes
           val path = dirPath.resolve(name) // todo: also can throw exception
           context.log.info(s"resolved <$dirPath>.resolve($name)=$path")
           Attributes.actorPath(path) match
            case Success(att: APath) =>
              val r: Ref = context.spawn(att, urlFor(name))
              Some((r, new Dir(contains + (name -> r), counters)))
            case Failure(err) =>
              err match
               case e: UnsupportedOperationException =>
                 context.log.error(
                   s"Cannot get BasicFileAttributes! JDK misconfigured on request <$path>",
                   e
                 )
                 None
               case e: IOException =>
                 context.log.info(s"IOException trying to get <$path>", e)
                 None
               case e: SecurityException =>
                 context.log.warn(
                   s"Security Exception on searching for attributes on <$path>.",
                   e
                 )
                 None
         }
      end getRef

      // state monad	fn. create symlink to new file (we don't have an actor for it yet)
      def createSymLink(linkName: String, linkTo: String): Try[(RRef, Dir)] =
        Attributes.createLink(dirPath, linkName, linkTo).map { att =>
           val ref = context.spawnSymLink(att, urlFor(linkName))
           (ref, new Dir(contains + (linkName -> ref), counters))
        }

      def createDir(newDirName: String): Try[(CRef, Dir)] =
        Attributes.createDir(dirPath, newDirName).map { att =>
           val ref = context.spawnDir(att, urlFor(newDirName))
           (ref, new Dir(contains + (newDirName -> ref), counters))
        }

      /** state monad fn - return counter for given base name so that one can create the next name
        * e.g. for base name `cat` and number 3 the next created file could be `cat_3`
        */
      def nextCounterFor(baseName: String): (Int, Dir) =
         val newcounter: Int = findSiblingCount(baseName) + 1
         (newcounter, new Dir(contains, counters + (baseName -> newcounter)))

      def countFile(countFileRoot: String): java.io.File =
        dirPath.resolve(countFileRoot + ".count").toFile

      /** You need to update the counter if you use it. This suggests one should have monadic
        * interface on the config data
        */
      def findSiblingCount(countFileRoot: String): Int =
        counters.get(countFileRoot).getOrElse {
          val cf = countFile(countFileRoot)
          Using(new BufferedReader(new FileReader(cf))) { (reader: BufferedReader) =>
            reader.readLine().toInt
          }.recover {
            case e: java.io.FileNotFoundException => cf.createNewFile(); 1
            case e: java.io.IOException => context.log.warn(s"Can't read count file <$cf>", e); 1
            case e: java.lang.NumberFormatException =>
              context.log.warn(s"error parsing counter in <$cf>"); 1
          }.getOrElse(0)
        }

      // todo: save such info to disk on shutdown
      def writeSiblingCount(countFileRoot: String, count: Int): Counter =
        counters + (countFileRoot -> (count + 1))

      def saveSiblingsCountFor(name: String, count: Int): Unit =
        Using(new BufferedWriter(new FileWriter(countFile(name)))) { (writer: BufferedWriter) =>
          writer.write(count.toString)
        } recover {
          case e => context.log.warn(s"Can't save counter value $count for <$countFile>", e)
        }

      /** Run HttpRequest Command aimed at this actor.
        *
        * This should be the only place the behavior can be changed from external requests. (It can
        * also be changed by receiving a shutdown message internally too).
        *
        * All changes to the system are built on `HTTPRequest`s. But we want to have higher level
        * Scripts based on Free Monads, that can be translated down to the HTTP level layer.
        *
        * If this function could just return the HttpResponse for the request with the Behavior then
        * the code calling this method could either just send the response back or do something like
        * transform the stream of the response into a Graph.
        *
        * But that won't work because POST creates a new resource and then has to pass the
        * HttpRequest on to the created resource, not on to the caller of the method. The result of
        * the POST (a new URL if successful) will then be something that the next command can use to
        * continue its work. This could be something like adding some triples to the newly created
        * acl.
        *
        * Hence the only way to be general enough is for this function to take a
        * `CmdMessage[Script[Plain,T]]`. That is we have a command whose top layer of execution is
        * an Plain akka HttpRequest. This function will evaluate that layer, then continue to the
        * next layer.
        *
        * Todo: instead of passing in the CmdMessage we pass all the arguments as it was getting too
        * difficult to get the typing right. When this works, it may to try to wrap those all back
        * up later.
        */
      protected def run[T](plainCmd: Plain[Script[T]], agent: Agent, replyTo: ActorRef[T])(using
          reg: ResourceRegistry,
          mat: Materializer,
          ec: ExecutionContext,
          fscm: cats.Functor[SolidCmd]
      ): Behavior[AcceptMsg] =
         context.log.info(s"in run received msg $plainCmd")
         import akka.stream.alpakka.file.scaladsl.Directory
         import BasicContainer.ttlPrefix
         import RDFMediaTypes.`text/turtle`
         import plainCmd.req
         import req.*
         // Here I just rebuild the CmdMessage, for convenience
         // todo: see above
         val pcmd: CmdMessage[T] = CmdMessage[T](plainCmd, agent, replyTo)
         if !uri.path.endsWithSlash then
            // this should have been dealt with by parent container, which should have tried conneg.
            val ldpcUri = uri.withPath(uri.path / "")
            pcmd.respondWith(HttpResponse(
              MovedPermanently,
              Seq(Location(ldpcUri)),
              entity = s"This resource is now a container at ${uri}"
            ))
            Behaviors.same
         else
            method match
             case OPTIONS =>
               pcmd.respondWith(HttpResponse( // todo, add more
                 NoContent,
                 `Accept-Post` :: AllowHeader :: Nil
               ))
               Behaviors.same
             case GET | HEAD => // return visible contents of directory
               // todo: I don't think this takes account of priorities. Check
               if req.headers[Accept].exists(_.mediaRanges.exists(_.matches(`text/turtle`))) then
                  pcmd.respondWith(HttpResponse(
                    OK,
                    aclHeader :: LinkHeaders :: `Accept-Post` :: AllowHeader :: Nil,
                    HttpEntity(
                      `text/turtle`.toContentType,
                      Source.combine(ttlPrefix, dirList.map(containsAsTurtle))(Concat(_)).map(s =>
                        ByteString(s)
                      )
                    )
                  ))
               else
                  pcmd.respondWith(HttpResponse(
                    UnsupportedMediaType,
                    aclHeader :: LinkHeaders :: `Accept-Post` :: AllowHeader :: Nil,
                    HttpEntity("We only support text/turtle media type at the moment")
                  ))
               Behaviors.same
             case POST => // create resource
               // todo: create sub container for LDPC Post
               import java.time.Clock
               import BasicContainer.{
                 createLinkNames,
                 filterLDPTypeLinks,
                 createNewResourceName,
                 ldpBC,
                 ldpr
               }
               import BasicContainer.{given Clock}
               val types = filterLDPTypeLinks(req.headers[Link])
               if types.contains(ldpBC) then
                  // todo: should one also parse the body first to check that it is well formed? Or should that
                  //   be left to the created actor - which may have to delete itself if not.
                  val newDirName = createNewResourceName(plainCmd.req)
                  val response: Try[(CRef, Dir)] = createDir(newDirName).recoverWith {
                    case e: FileAlreadyExistsException => // this is the pattern of a state monad!
                      val (nextId, newDir) = nextCounterFor(newDirName)
                      val nextDirName      = newDirName + "_" + nextId
                      newDir.createDir(nextDirName)
                  }
                  response match
                   case Success((cref, dir)) =>
                     cref.actor ! PostCreation(cref.att.path, pcmd)
                     dir.start
                   case Failure(e) =>
                     pcmd.respondWith(HttpResponse(
                       InternalServerError,
                       Seq(),
                       HttpEntity(
                         `text/x-java-source`.withCharset(HttpCharsets.`UTF-8`),
                         e.toString
                       )
                     ))
                     Behaviors.same
               else // create resource
                  val (linkName: String, linkTo: String) = createLinkNames(plainCmd.req)
                  val response: Try[(RRef, Dir)] = createSymLink(linkName, linkTo).recoverWith {
                    case e: FileAlreadyExistsException => // this is the pattern of a state monad!
                      val (nextId, newDir) = nextCounterFor(linkName)
                      val nextLinkName     = linkName + "_" + nextId
                      newDir.createSymLink(
                        nextLinkName,
                        linkToName(nextLinkName, entity.contentType)
                      )
                  }
                  response match
                   case Success((ref, dir)) =>
                     ref.actor ! PostCreation(dirPath.resolve(ref.att.to), pcmd)
                     dir.start
                   case Failure(e) =>
                     pcmd.respondWith(HttpResponse(
                       InternalServerError,
                       Seq(),
                       HttpEntity(
                         `text/x-java-source`.withCharset(HttpCharsets.`UTF-8`),
                         e.toString
                       )
                     ))
                     Behaviors.same
               end if
             case DELETE =>
               if dirIsEmpty then
                  Files.move(
                    dirPath,
                    dirPath.resolveSibling("" + dirPath.getFileName() + ".archive")
                  )
                  pcmd.respondWith(HttpResponse(NoContent, Seq(), entity = s"resource deleted"))
                  Behaviors.stopped
               else
                  pcmd.respondWith(HttpResponse(
                    Conflict,
                    aclHeader :: LinkHeaders :: `Accept-Post` :: AllowHeader :: Nil,
                    entity = HttpEntity(
                      `text/turtle`.toContentType,
                      Source.combine(ttlPrefix, dirList.map(containsAsTurtle))(Concat(_))
                        .map(s => ByteString(s))
                    )
                  ))
                  Behaviors.same
             // todo: create new PUT request and forward to new actor?
             // or just save the content to the file?
             case _ =>
               pcmd.respondWith(HttpResponse(
                 NotImplemented,
                 Seq(),
                 entity = s"have not implemented  ${plainCmd.req.method} for ${plainCmd.req.uri}"
               ))
               Behaviors.same
      end run

      protected def routeHttpReq(msg: RouteMsg): Behavior[AcceptMsg] =
        msg.nextRoute match
         case doit @ WannaDo(_) =>
           doit.msg.target.fileName match
            case None                   => forwardToContainer(msg.nextSegment, doit)
            case Some(s) if s(0) == '.' => forwardToContainer(msg.nextSegment, doit)
            case _                      => forwardMsgToResourceActor(msg.nextSegment, doit)
         case route: Route => forwardToContainer(msg.nextSegment, route)

      def forwardToContainer(name: String, route: Route): Behavior[AcceptMsg] =
         context.log.info(s"in forwardToContainer($name, $route)")
         if name.indexOf('.') > 0 then
            route.msg.respondWith(HttpResponse(
              NotFound,
              entity = HttpEntity(
                "This Solid server serves no resources with a '.' char in path segments (except for the last `file` segment)."
              )
            ))
            Behaviors.same
         else
            getRef(name) match
             case Some(x, dir) =>
               x match
                case CRef(att, actor) => actor ! route
                case SMRef(
                      att,
                      actor
                    ) => // server managed resource, this is not a Container, but is "owned" by this container.
                  context.log.info(s"we have a Server Managed Resource SMRef($att, $actor)")
                  route match
                   case wd: WannaDo => // we pass on to the server managed actor
                     context.log.info(s"We are dispatching SMRef to $actor")
                     actor ! wd
                   case r: Route => // the path passes through a file, so it must end here
                     context.log.error(
                       "We should never arrive at this point!! Look at how we got here"
                     )
                     r.msg.respondWith(HttpResponse(
                       InternalServerError,
                       Seq(),
                       s"Resource with URI ${r.msg.target} does not exist"
                     ))
                case RRef(att, actor) => // there is no container, so redirect to resource
                  route match
                   case WannaDo(cmd) => // we're at the path end, so we can redirect
                     val redirectTo = cmd.target.withoutSlash
                     route.msg.redirectTo(
                       redirectTo,
                       "There is no container here. we will redirect to the resource"
                     )
                   case r: Route => // the path passes through a file, so it must end here
                     r.msg.respondWith(HttpResponse(
                       NotFound,
                       Seq(),
                       s"Resource with URI ${r.msg.target} does not exist"
                     ))
               dir.start
             case None =>
               route.msg.respondWith(
                 HttpResponse(
                   NotFound,
                   entity =
                     HttpEntity(s"""Resource with URI ${route.msg.target} does not exist.""")
                 )
               )
               Behaviors.same
      end forwardToContainer

      /** Forward message to child resource (not container)
        * @param name
        *   Content-Negotiation root name: e.g. `cat` can return `cat.jpg` or `cat.png`
        * @param wannaDo
        *   the HttpRequest
        * @return
        *   A new Behavior, updated if a new child actor is created.
        */
      protected def forwardMsgToResourceActor(name: String, wannaDo: WannaDo): Behavior[AcceptMsg] =
         context.log.info(s"in  forwardMsgToResourceActor($name, $wannaDo")
         val dotLessName = actorNameFor(name)
         getRef(dotLessName) match
          case Some(x, dir) =>
            x match
             case CRef(_, actor) =>
               wannaDo.msg.respondWith {
                 if dotLessName == name then
                    val uri = wannaDo.msg.target
                    HttpResponse(MovedPermanently, Seq(Location(uri.withPath(uri.path / ""))))
                 else HttpResponse(NotFound)
               }
             case RRef(_, actor) => actor ! wannaDo
             case SMRef(
                   att,
                   actor
                 ) => // server managed resource, this is not a Container, but is "owned" by this container.
               context.log.info(s"we have a Server Managed Resource SMRef($att, $actor)")
               actor ! wannaDo
            dir.start
          case None =>
            wannaDo.msg.respondWith(HttpResponse(
              NotFound,
              entity = HttpEntity(
                `text/plain`.withCharset(`UTF-8`),
                s"""Resource with URI ${wannaDo.msg.target} does not exist.
                   |Try posting to <${containerUrl}> container first.""".stripMargin
              )
            ))
            Behaviors.same
      end forwardMsgToResourceActor

      /** Given a request for resource `name` find out what its root is. We will just take the name
        * to be everything up to the first dot (`.`) - to be made more flexible later. (an initial
        * dot, does not count) This makes a lot of decisions here much simpler. This would make PUT
        * to create new resources very brittle unless the server can tell the client what the
        * restrictions are.
        */
      def actorNameFor(resourceName: String): String =
        if resourceName.size > 1 then
           s"${resourceName(0)}${resourceName.substring(1).takeWhile(_ != '.')}"
        else resourceName
