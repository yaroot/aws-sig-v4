package aws

case class Service(value: String)

object Service {
  object Aws {
    val DynamoDB = Service("dynamodb")
    val S3       = Service("s3")

    val BASE_DOMAIN = "amazonaws.com"

    def urlFor(service: Service, region: Region): String =
      s"https://${service.value}.${region.value}.${BASE_DOMAIN}"
  }

  object Aliyun {
    val OSS = Service("oss")

    val BASE_DOMAIN = "aliyuncs.com"

    def urlFor(service: Service, region: Region): String =
      s"https://${service.value}-${region.value}.${BASE_DOMAIN}"
  }
}
