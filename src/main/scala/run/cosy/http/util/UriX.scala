package run.cosy.http.util

import akka.http.scaladsl.model.Uri
import Uri._

object UriX:
	extension (uri: Uri)

		/** return filename if exists - can return None, for urls without paths or paths ending in `/` */
		def fileName: Option[String] = uri.path.reverse match
			case Path.Segment(head,tail) => Some(head)
			case _ => None

		/** remove uri without the final slash, or the same */
		def withoutSlash: Uri =
			val rev: Uri.Path = uri.path.reverse
			rev match
				case Uri.Path.Slash(path) => uri.withPath(path.reverse)
				case _ => uri

		/**
		 * replace fileName with Name in Uri or else place filename after slash or add an initial slash
		 * Todo: improve - this definintion feels very ad-hoc ...
		 **/
		def sibling(name: String) =
			val rev: Uri.Path = uri.path.reverse
			val newPath = rev match
				case Path.Slash(path) => uri.path ++ Path(name)
				case Path.Empty => Path.Slash(Path(name))
				case Path.Segment(head, tail) => Path.Segment(name,tail).reverse
			uri.withPath(newPath)

		/**
		 * @param other uri
		 * @return true if other has this uri as part (ignoring query paramters)
		 */
		def ancestorOf(other: Uri): Boolean =
			(other.scheme == uri.scheme) && (other.authority == uri.authority) &&
				(other.path.startsWith(uri.path))

		def /(segment: String): Uri = uri.withPath(uri.path/segment)
		def ?/(segment: String): Uri = uri.withPath(uri.path?/segment)


	extension (path: Uri.Path)
		/**
		 * @return the "container" of this resource if it is not a container, or itself.
		 *         i.e. Path./("hello).container == Path./
		 *         and Path./.container == Path./ 
		 **/
		def container: Uri.Path =
			if path.endsWithSlash then path else path.reverse.tail.reverse
