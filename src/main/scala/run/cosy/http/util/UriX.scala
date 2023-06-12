/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http.util

import akka.http.scaladsl.model.Uri
import Uri.*
import akka.http.scaladsl.model.Uri.Path.Slash

import scala.annotation.tailrec

object UriX:

   extension (path: Uri.Path)
      def diff(longerPath: Uri.Path): Option[Uri.Path] =
         @tailrec def rec(path: Uri.Path, remaining: Uri.Path): Option[Uri.Path] =
           (path, remaining) match
            case (Path.Empty, remaining)                    => Some(remaining)
            case (Path.Slash(Path.Empty), Path.Slash(rest)) => Some(rest)
            case (Path.Slash(tail), Path.Slash(longerTail)) => rec(tail, longerTail)
            case (Path.Segment(x, tail), Path.Segment(y, longerTail)) if x == y =>
              rec(tail, longerTail)
            case _ => None
         if path.length > longerPath.length then None
         else rec(path, longerPath)

      /** transform to path as list of strings followed by optional file name */
      def components: (List[String], Option[String]) =
         @tailrec
         def rec(path: Uri.Path, dirs: List[String]): (List[String], Option[String]) =
           path match
            case Path.Empty                     => (dirs, None)
            case Path.Slash(Path.Empty)         => (dirs, None)
            case Path.Slash(tail)               => rec(tail, dirs)
            case Path.Segment(dir, Slash(tail)) => rec(tail, dir :: dirs)
            case Path.Segment(file, Path.Empty) => (dirs, Some(file))
         val (p, f) = rec(path, Nil)
         (p.reverse, f)

   extension (uri: Uri)

      /** return filename if exists - can return None, for urls without paths or paths ending in `/`
        */
      def fileName: Option[String] = uri.path.reverse match
       case Path.Segment(head, tail) => Some(head)
       case _                        => None

      /** find the container for this uri */
      def container: Uri = uri.withPath(uri.path.container)

       /** remove uri without the final slash, or the same */
      def withoutSlash: Uri =
         val rev: Uri.Path = uri.path.reverse
         rev match
          case Uri.Path.Slash(path) => uri.withPath(path.reverse)
          case _                    => uri

      /** add slash to the end the final slash, or the same */
      def withSlash: Uri =
//        uri.withPath(uri.path ++ Uri.Path./)
        val rev = uri.path.reverse
        rev match
          case Uri.Path.Empty => uri.withPath(Uri.Path./)
          case Uri.Path.Segment(name,tail) => uri.withPath(Slash(Uri.Path.Segment(name,tail)).reverse)
          case _ => uri

      /** replace fileName with Name in Uri or else place filename after slash or add an initial
        * slash Todo: improve - this definintion feels very ad-hoc ...
        */
      def sibling(name: String) =
         val rev: Uri.Path = uri.path.reverse
         val cleaned       = if name.startsWith("/") then name.drop(1) else name
         val newPath = rev match
          case Path.Slash(path)         => uri.path ++ Path(cleaned)
          case Path.Empty               => Path.Slash(Path(cleaned))
          case Path.Segment(head, tail) => Path.Segment(cleaned, tail).reverse
         uri.withPath(newPath)

      /** @param other
        *   uri
        * @return
        *   true if other has this uri as part (ignoring query paramters)
        */
      def ancestorOf(other: Uri): Boolean =
        (other.scheme == uri.scheme) && (other.authority == uri.authority) &&
          (other.path.startsWith(uri.path))

      def /(segment: String): Uri = uri.withPath(uri.path / segment)
      def ?/(segment: String): Uri = uri.withPath(uri.path ?/ {
        if segment.startsWith("/") then segment.drop(1) else segment
      })

   extension (path: Uri.Path)
     /** @return
       *   the "container" of this resource if it is not a container, or itself.
       * i.e. Path./("hello).container == Path./ and Path./.container == Path./
       */
     def container: Uri.Path =
       if path.endsWithSlash then path else path.reverse.tail.reverse
