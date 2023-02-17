import mill._
import mill.scalalib._
import mill.define._
import mill.scalalib.scalafmt.ScalafmtModule

val Http4sVersion           = "0.23.18"
val WartremoverVersion      = "3.0.8"
val BetterMonadicForVersion = "0.3.1"

val ScalaVersion = "2.13.10"

object Shared {
  object Deps {
    val wartremover = Seq(
      ivy"org.wartremover::wartremover:$WartremoverVersion"
    )
    val bmc         = Seq(
      ivy"com.olegpy::better-monadic-for:$BetterMonadicForVersion"
    )
    val http4s      = Seq(
      ivy"org.http4s::http4s-core:$Http4sVersion"
    )
  }

  val scalacOptions = Seq(
    "-encoding",
    "utf8",
    "-feature",
    "-unchecked",
    "-language:existentials",
    "-language:experimental.macros",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-Xlint:adapted-args",
    "-Xlint:constant",
    "-Xlint:delayedinit-select",
    "-Xlint:deprecation",
    "-Xlint:doc-detached",
    "-Xlint:implicit-recursion",
    "-Xlint:implicit-not-found",
    "-Xlint:inaccessible",
    "-Xlint:infer-any",
    "-Xlint:missing-interpolator",
    "-Xlint:nullary-unit",
    "-Xlint:option-implicit",
    "-Xlint:package-object-classes",
    "-Xlint:poly-implicit-overload",
    "-Xlint:private-shadow",
    "-Xlint:stars-align",
    "-Xlint:strict-unsealed-patmat",
    "-Xlint:type-parameter-shadow",
    "-Xlint:-byname-implicit",
    "-Wdead-code",
    "-Wextra-implicit",
    "-Wnumeric-widen",
    "-Wvalue-discard",
    "-Wunused:nowarn",
    "-Wunused:implicits",
    "-Wunused:explicits",
    "-Wunused:imports",
    "-Wunused:locals",
    "-Wunused:params",
    "-Wunused:patvars",
    "-Wunused:privates",
    "-Xfatal-warnings",
    "-Ymacro-annotations",
    "-Xsource:3",
    "-P:wartremover:traverser:org.wartremover.warts.AsInstanceOf",
    "-P:wartremover:traverser:org.wartremover.warts.EitherProjectionPartial",
    "-P:wartremover:traverser:org.wartremover.warts.Null",
    "-P:wartremover:traverser:org.wartremover.warts.OptionPartial",
    "-P:wartremover:traverser:org.wartremover.warts.Product",
    "-P:wartremover:traverser:org.wartremover.warts.Return",
    "-P:wartremover:traverser:org.wartremover.warts.TryPartial",
    "-P:wartremover:traverser:org.wartremover.warts.Var"
  )
}

trait CommonScalaModule extends ScalaModule with ScalafmtModule {
  override def scalaVersion: T[String] = T(ScalaVersion)
}

object `aws-sig-v4` extends CommonScalaModule {
  override def scalacOptions: Target[Seq[String]] = T(Shared.scalacOptions)
  override def compileIvyDeps                     = T(Shared.Deps.wartremover ++ Shared.Deps.bmc)
  override def scalacPluginIvyDeps                = T(Shared.Deps.wartremover ++ Shared.Deps.bmc)

  override def ivyDeps: T[Agg[Dep]] = T(
    Agg.from(Shared.Deps.http4s)
  )
}
