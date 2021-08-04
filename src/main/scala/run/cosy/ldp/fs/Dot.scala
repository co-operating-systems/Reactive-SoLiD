package run.cosy.ldp.fs

/** to allow string pattern matching to Integer */
object AsInt:
	def unapply(ver: String): Option[Int] = ver.toIntOption

object Dot:
	val aclExt = "acl"
	def split(name: String): List[String] = name.split('.').toList


/**
 * Capture convention for Versioned Symbolic link file names.
 * Examples of maps from base names to full representations:
 *   - `card` -> `card.2.ttl`
 *   - `card.acl` -> `card.acl.3.ttl`
 *   - `.acl` -> `.acl.4.ttl`
 */
class Dot(val baseName: String):
	import Dot.*
	lazy val parts: List[String] = split(baseName)
	lazy val isContainerAcl: Boolean = parts.startsWith(List("",aclExt))
	
	object File:
	/** return the version and extension for the file */
		def unapply(name: String): Option[(Int, String)] =
			remaining(name).collect {
				case List() => (0, "") //is this acceptable? not sure
				case List(AsInt(version), extension) => (version, extension)
			}

	class Version(n: Int, ext: String):
		def name: String = (parts ::: List(n.toString, ext)).mkString(".")


	/** return the remaining dot-parts of the file `name`, if this starts with the same dot.path */
	def remaining(name: String): Option[List[String]] =
		val fparts = split(name)
		if fparts.startsWith(parts) && fparts.size >= parts.size then
			val remaining = fparts.drop(parts.size)
			Some(remaining)
		else None
		
	def isACR: Boolean = parts.lastOption.map(_ == aclExt).getOrElse(false)	

	/** is the given pathName an acr of this resource (yes if it continues with ".acl" */
	def hasACR(pathName: String): Boolean =
		remaining(pathName).flatMap(_.headOption.map(_ == aclExt)).getOrElse(false)

end Dot
