package aws.signature.v4

import cats.data.NonEmptyList
import cats.effect.*
import cats.implicits.*
import org.http4s.*
import aws.signature.v4.kernel
import fs2.{Stream, Chunk}
import org.http4s.util.Renderer
import org.typelevel.ci.*
import scodec.bits.ByteVector

sealed trait BodyStrategy
object BodyStrategy {
  case class IgnoreBody()               extends BodyStrategy
  case class PreComputed(value: String) extends BodyStrategy
  case class Default()                  extends BodyStrategy
  case class HasBody(body: ByteVector)  extends BodyStrategy
}

trait Signer[F[_]] {
  def sign(req: Request[F], bodyStrategy: BodyStrategy): F[Request[F]]
  def sign(req: Request[F]): F[Request[F]]
}

object Signer {

  val EXCLUDE_HEADERS: Vector[CIString] = Vector(
    ci"Accept-Encoding",
    ci"Authorization",
    ci"User-Agent"
  )

  val X_AMZ_DATE = ci"X-Amz-Date"

  object Strategy {
    import BodyStrategy.*
    def ignore: BodyStrategy                             = IgnoreBody()
    def preComputedHash(hash: ByteVector): BodyStrategy  = PreComputed(kernel.Signing.hex(hash.toArray))
    def bodyHashPlaceholder(value: String): BodyStrategy = PreComputed(value)
    def default: BodyStrategy                            = Default()
    def body(body: ByteVector): BodyStrategy             = HasBody(body)
  }

  def isSignableHeader(h: CIString): Boolean = {
    !EXCLUDE_HEADERS.contains(h)
  }

  def renderDate(instant: java.time.Instant): String = {
    kernel.Signing.amzDate(instant)
  }

  def unsafeHash(input: Array[Byte]): String = {
    kernel.Signing.hex {
      kernel.Signing.sha256(input)
    }
  }

  def unChunkHttp[F[_], A <: Message[F]](m: A): A#Self = {
    val newConfig = m.headers
      .get[headers.`Transfer-Encoding`]
      .map(_.values.toList)
      .toList
      .flatten
      .filter(_ != TransferCoding.chunked)

    NonEmptyList
      .fromList(newConfig)
      .fold(m.removeHeader(headers.`Transfer-Encoding`.name)) { values =>
        m.putHeaders(headers.`Transfer-Encoding`(values))
      }
  }

  def default[F[_]: Sync](
    region: String,
    service: String,
    accessKey: String,
    accessSecret: String
  ): Signer[F] = new Signer[F] {
    def now: F[java.time.Instant] = Clock[F].realTime.map(a => java.time.Instant.ofEpochMilli(a.toMillis))

    def getBody(req: Request[F], strategy: BodyStrategy): F[(Option[ByteVector], Option[String])] = {
      import scodec.bits.ByteVector as BV
      strategy match {
        case BodyStrategy.IgnoreBody()      => (none[BV], none[String]).pure[F]
        case BodyStrategy.PreComputed(hash) => (none[BV], hash.some).pure[F]
        case BodyStrategy.HasBody(body)     => (body.some, none[String]).pure[F]
        case BodyStrategy.Default()         =>
          req.body.chunks.compile.toVector
            .map(Chunk.concat(_))
            .map(_.toByteVector.some -> none[String])
      }
    }

    def transformRequest(
      unsigned: Request[F],
      body: Option[ByteVector],
      strategy: BodyStrategy,
      httpDate: String,
      sig: kernel.V4Signature
    ): Request[F] = {
      val signed: Request[F] = unsigned
        .putHeaders(
          Header.Raw(headers.Authorization.name, sig.renderAuth),
          Header.Raw(ci"Date", httpDate)
        )

      // replace the body in default strategy
      body.filter(_.size > 0).fold(signed) { content =>
        strategy match {
          case BodyStrategy.Default() => signed.withBodyStream(Stream.chunk(Chunk.byteVector(content)))
          case _                      => signed
        }
      }
    }

    def sign(req: Request[F]): F[Request[F]] =
      sign(req, Strategy.default)

    def sign(req: Request[F], bodyStrategy: BodyStrategy): F[Request[F]] = {
      for {
        instant          <- now
        req0              = req.putHeaders(Header.Raw(X_AMZ_DATE, renderDate(instant)))
        (body, bodyHash) <- getBody(req0, bodyStrategy)
        baseHeaders       = req0.headers.headers
                              .filter(h => isSignableHeader(h.name))
                              .toVector
                              .map(h => h.name.toString -> h.value)
        hostHeader        = req0.uri.host.map(a => "Host" -> a.value)
        httpDate          = Renderer.renderString(instant)
        additionalHeaders = Vector(hostHeader).unite
        queries           = req0.uri.query.pairs.map(a => a._1 -> a._2.getOrElse(""))
        httpPath          = req0.uri.path.renderString
        signable          = kernel.Signable(
                              region      = region,
                              service     = service,
                              method      = req.method.name,
                              httpPath    = if (httpPath.isEmpty) "/" else httpPath,
                              queryParams = queries,
                              baseHeaders = baseHeaders ++ additionalHeaders,
                              body        = body.map(_.toArray),
                              bodyHash    = bodyHash,
                              accessKey   = accessKey,
                              secretKey   = accessSecret,
                              instant     = instant
                            )
        sig              <- Sync[F].delay(kernel.Signing.sign(signable))
      } yield transformRequest(req0, body, bodyStrategy, httpDate, sig)
    }

  }
}
