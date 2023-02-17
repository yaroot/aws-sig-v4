/*
  kernel implementation of AWS V4 signing, only depends on JDK
  (could be adapt to scala.js for nodejs)
  inspired by smithy4s
 */
package aws.signature.v4.kernel

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.{Instant, ZoneOffset}
import java.time.format.{DateTimeFormatter, DateTimeFormatterBuilder}
import java.time.temporal.ChronoField
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

case class AwsCredential(key: String, secret: String)

/*
baseHeaders:
  Content-Type
  Host
  X-Amz-Date
  X-Amz-Security-Token: STS token? can be empty
  X-Amz-Target: serviceName + "." + operationName

for S3 request with content, the `x-amz-content-sha256` header can be set to
  - <sha256 hex digest>: the hash of the payload
  - UNSIGNED-PAYLOAD: not signing the payload
  - STREAMING-AWS4-HMAC-SHA256-PAYLOAD: chunking (streaming)
 */
case class Signable(
  region: String,                     // eg: us-east-1
  service: String,                    // eg: iam
  method: String,                     // GET, POST, etc
  httpPath: String,                   // path or empty
  queryParams: Seq[(String, String)], // query params
  baseHeaders: Seq[(String, String)], // headers to sign
  body: Option[Array[Byte]],          // body if available
  bodyHash: Option[String],           // pre computed sha256 digest (lower case hex encoding) (or special placeholder)
  accessKey: String,                  // access key
  secretKey: String,                  // secret key
  instant: Instant
)

/*
The result can be used in presigned request if the following headers are signed:
  X-Amz-Expires
 */
case class V4Signature(
  scheme: String,
  credentialScope: String,
  signingHeader: String,
  signature: String,
  accessKey: String
) {
  def renderAuth: String = {
    s"$scheme Credential=$accessKey/$credentialScope, SignedHeaders=$signingHeader, Signature=$signature"
  }
}

object Signing {
  type Bytes = Array[Byte]
  val AlgorithmV4 = "AWS4-HMAC-SHA256"

  val amzDateFormatter: DateTimeFormatter = {
    import ChronoField.*
    new DateTimeFormatterBuilder()
      .appendValue(YEAR, 4)
      .appendValue(MONTH_OF_YEAR, 2)
      .appendValue(DAY_OF_MONTH, 2)
      .appendLiteral('T')
      .appendValue(HOUR_OF_DAY, 2)
      .appendValue(MINUTE_OF_HOUR, 2)
      .appendValue(SECOND_OF_MINUTE, 2)
      .appendLiteral('Z')
      .toFormatter
      .withZone(ZoneOffset.UTC)
  }

  val dateStampFormatter: DateTimeFormatter = {
    import ChronoField.*
    new DateTimeFormatterBuilder()
      .appendValue(YEAR, 4)
      .appendValue(MONTH_OF_YEAR, 2)
      .appendValue(DAY_OF_MONTH, 2)
      .toFormatter
      .withZone(ZoneOffset.UTC)
  }

  val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

  def hmacSHA256(payload: Bytes, key: Bytes): Bytes = {
    val algo = "HmacSHA256"
    val mac  = javax.crypto.Mac.getInstance(algo)
    mac.init(new SecretKeySpec(key, algo))
    mac.doFinal(payload)
  }

  def sha256(payload: Bytes): Bytes =
    MessageDigest
      .getInstance("SHA-256")
      .digest(payload)

  def b64(data: Bytes): String = Base64.getEncoder.encodeToString(data)
  def bs(a: String): Bytes     = a.getBytes(StandardCharsets.UTF_8)
  def hex(xs: Bytes): String   = xs.map("%02x".format(_)).mkString

  def deriveSigningKey(secret: String, dateStamp: String, region: String, service: String): Bytes = {
    val kSecret  = bs("AWS4" + secret)
    val kDate    = hmacSHA256(bs(dateStamp), kSecret)
    val kRegion  = hmacSHA256(bs(region), kDate)
    val kService = hmacSHA256(bs(service), kRegion)
    val kSigning = hmacSHA256(bs("aws4_request"), kService)
    kSigning
  }

  def amzDate(instant: Instant): String   = amzDateFormatter.format(instant)
  def dateStamp(instant: Instant): String = dateStampFormatter.format(instant)

  def deriveCanonicalRequest(signable: Signable): (String, String) = {
    val canonQueryString =
      if (signable.queryParams.isEmpty) ""
      else {
        signable.queryParams
          .sortBy(_._1)
          .map { case (a, b) =>
            URIEncoder.encode(a) + "=" + URIEncoder.encode(b)
          }
          .mkString("&")
      }

    val sortedHeaders =
      signable.baseHeaders
        .map { case (a, b) =>
          a.toLowerCase -> b
        }
        .sortBy(_._1)

    val canonHeaders =
      sortedHeaders
        .map { case (a, b) =>
          s"${a}:${b.trim}"
        }
        .mkString("\n")

    val singedHeaders =
      sortedHeaders.map(_._1).mkString(";")

    val payloadHash =
      signable.bodyHash
        .getOrElse {
          hex { sha256(signable.body.getOrElse(Array.emptyByteArray)) }
        }

    val canonRequest = Vector(
      signable.method,
      signable.httpPath,
      canonQueryString,
      canonHeaders,
      "", // append \n to canonHeaders
      singedHeaders,
      payloadHash
    ).mkString("\n")

    canonRequest -> singedHeaders
  }

  def deriveStringToSign(signable: Signable): (String, String, String) = {
    val (canonRequest, headersToSign) = deriveCanonicalRequest(signable)

    val credentialScope = Vector(
      dateStamp(signable.instant),
      signable.region,
      signable.service,
      "aws4_request"
    ).mkString("/")

    val stringToSign = Vector(
      AlgorithmV4,
      amzDate(signable.instant),
      credentialScope,
      hex { sha256(bs(canonRequest)) }
    ).mkString("\n")

    (stringToSign, headersToSign, credentialScope)
  }

  def sign(signable: Signable): V4Signature = {
    val (stringToSign, headersToSign, credentialScope) = deriveStringToSign(signable)
    val signingKey                                     = deriveSigningKey(
      signable.secretKey,
      dateStamp(signable.instant),
      signable.region,
      signable.service
    )
    val signature                                      = hex { hmacSHA256(bs(stringToSign), signingKey) }

    V4Signature(
      AlgorithmV4,
      credentialScope,
      headersToSign,
      signature,
      signable.accessKey
    )
  }
}
