package pds.identity

import cats.effect.{IO, Ref}
import io.circe.Json
import org.http4s.*
import org.http4s.client.Client
import org.typelevel.ci.CIString

/** Outbound POSTs go to the PLC directory, relays and the email endpoint, all of
  * which are entitled to refuse a body that is not labelled JSON.
  */
class NetSuite extends munit.CatsEffectSuite:
  private def recording(seen: Ref[IO, Vector[(String, Option[String], String)]]) =
    Client.fromHttpApp(HttpApp[IO] { request =>
      request.bodyText.compile.string.flatMap { body =>
        seen.update(_ :+ (
          request.uri.renderString,
          request.headers.get(CIString("Content-Type")).map(_.head.value),
          body)).as(Response[IO](Status.Ok).withEntity("{}"))
      }
    })

  test("a posted body is labelled as JSON, not as text") {
    for
      seen <- Ref.of[IO, Vector[(String, Option[String], String)]](Vector.empty)
      net = new Net(recording(seen), allowPrivate = true)
      result <- net.postJson("https://plc.directory/did:plc:abc",
        Json.obj("type" -> Json.fromString("plc_operation")))
      recorded <- seen.get
    yield
      assert(clue(result).isRight)
      assertEquals(recorded.length, 1)
      val (_, contentType, body) = recorded.head
      assertEquals(contentType.map(_.takeWhile(_ != ';')), Some("application/json"))
      assertEquals(body, """{"type":"plc_operation"}""")
  }

  test("caller headers survive alongside the content type") {
    for
      seen <- Ref.of[IO, Vector[(String, Option[String], String)]](Vector.empty)
      net = new Net(recording(seen), allowPrivate = true)
      _ <- net.postJson("https://mail.example.com/send", Json.obj("to" -> Json.fromString("a@b.c")),
        Headers(net.header("Authorization", "Bearer secret"),
          net.header("Idempotency-Key", "abcdef0123456789")))
      recorded <- seen.get
    yield
      assertEquals(recorded.head._2.map(_.takeWhile(_ != ';')), Some("application/json"))
  }
