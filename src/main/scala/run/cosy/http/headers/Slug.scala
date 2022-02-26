package run.cosy.http.headers

import _root_.akka.http.scaladsl.model.HttpHeader
import _root_.akka.http.scaladsl.model.headers.{CustomHeader, RawHeader}
import _root_.run.cosy.akka.http.headers.{BetterCustomHeader, BetterCustomHeaderCompanion, Encoding}
import _root_.run.cosy.akka.http.headers.Encoding.{UnicodeString, UrlEncoded}

import _root_.scala.util.Try
import _root_.run.cosy.akka.http.headers.Encoding.*

/**
 * The Slug header was first defined in [[https://tools.ietf.org/html/rfc5023#section-9.7 RFC 5023: Atom Publishing Protocol]]
 *
 * @param text: We manipulate Slug on sending and on receiving with Unicode Strings
 */
final case class Slug(text: UnicodeString) extends BetterCustomHeader[Slug]:
	override def renderInRequests = true
	override def renderInResponses = false
	override val companion = Slug
	override def value: String = text.urlEncode.toString


object Slug extends BetterCustomHeaderCompanion[Slug]:
	override val name = "Slug"

	//override try to generalise later
	def parse(value: UrlEncoded): Try[UnicodeString] = value.decode

	// try to generalise later
	def unapply(h: HttpHeader): Option[UnicodeString] = h match {
		case _: (RawHeader | CustomHeader) if (h.lowercaseName == lowercaseName) =>
			parse(h.value.asEncoded).toOption
		case _ => None
	}

