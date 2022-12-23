import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator

import scala.util.Try

class RsaSigner():
  
  val keyT: Try[RSAKey] = Try{
    new RSAKeyGenerator(4096).generate
  }
  
  def getPublicKey =
    keyT.map(_.toPublicJWK.toJSONString)
  
  def sign(data: String) =
     for
       jwsObj <- Try {
          val header = new JWSHeader(JWSAlgorithm.PS512)
          val payload = new Payload(data)
          new JWSObject(header, payload)
         }
       key <- keyT
     yield
       jwsObj.sign(new RSASSASigner(key))
       jwsObj.serialize

val sig = new RsaSigner()
println("pubKey="+sig.getPublicKey)

val signature = sig.sign("how are you dear old friend")