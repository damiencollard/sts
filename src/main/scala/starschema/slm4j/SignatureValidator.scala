package starschema.slm4j

import java.security._
import java.security.spec.X509EncodedKeySpec

import scala.util.{Success, Try, Failure}
import scala.util.control.NonFatal

import Delim._
import Util2._

object SignatureValidator {
  sealed trait SignatureVerification
  case class SignatureMatch(lines: Array[String]) extends SignatureVerification
  case object SignatureMismatch extends SignatureVerification
}

class SignatureValidator {
  import SignatureValidator._

  /** Verifies a signed license file against a public key.
    * Returns the license text lines on success.
    */
  def verifyLicense(signedFileName: String, publicKeyFileName: String): Try[SignatureVerification] =
    for (
      publicKey    <- readPublicKey(publicKeyFileName);
      signedText   <- readSignedText(signedFileName, publicKey);
      ok           <- verifySignedText(signedText, publicKey)
    ) yield {
      if (ok) SignatureMatch(signedText.lines) else SignatureMismatch
    }

  def readPublicKey(fileName: String): Try[PublicKey] =
    readFileContents(fileName, keepLines = false) map { publicKeyString =>
      KeyFactory.getInstance("DSA").generatePublic(
        new X509EncodedKeySpec(Base64Coder.decode(publicKeyString)))
    } recoverWith {
      case NonFatal(e) =>
        Failure(new SlmException("Error reading public key file: " + e.getMessage))
    }

  private def readSignedText(fileName: String, publicKey: PublicKey): Try[SignedText] =
    for (lines     <- readLines(fileName);
         textLines <- extractLicense(lines);
         sig       <- extractSignature(lines, publicKey))
      yield SignedText(textLines, sig)

  private def extractLicense(lines: Array[String]): Try[Array[String]] =
    Success(extractLines(lines, LICENSE_BEGIN, LICENSE_END))

  private def extractSignature(lines: Array[String], publicKey: PublicKey): Try[Array[Byte]] = Try {
    val sig = Signature.getInstance("SHA1withDSA")
    sig.initVerify(publicKey)

    val sigLines = extractLines(lines, SIGNATURE_BEGIN, SIGNATURE_END)
    val sb = new StringBuilder
    sigLines foreach sb.append

    Base64Coder.decode(sb.toString())
  } recoverWith {
    case NonFatal(e) =>
      Failure(new SlmException("Error initializing signature: " + e.getMessage))
  }

  private def verifySignedText(signedText: SignedText, publicKey: PublicKey): Try[Boolean] =
    Try {
      val computedSig = Signature.getInstance("SHA1withDSA")
      computedSig.initVerify(publicKey)
      signedText.lines foreach { line => computedSig.update(line.getBytes, 0, line.getBytes.length) }
      computedSig.verify(signedText.signature)
    } recoverWith {
      case NonFatal(e) =>
        Failure(new SlmException("Failed verifying signature: " + e.getMessage))
    }
}
