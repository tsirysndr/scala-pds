package pds.accounts

import io.circe.Json

/** The delivery endpoint accepts exactly `{to, subject, text}`, so what goes on
  * the wire is a contract with a service outside this repository.
  */
class EmailSuite extends munit.FunSuite:
  private def payload(purpose: String, extra: (String, Json)*) =
    Json.obj(
      Vector(
        "to" -> Json.fromString("alice@example.com"),
        "purpose" -> Json.fromString(purpose)
      ) ++ extra*
    )

  private val token = "token" -> Json.fromString("ABC123-DEF456")

  test("a code message carries only the fields the endpoint accepts") {
    val rendered = Email.render("pds.example.com", payload("reset-password", token)).get
    assertEquals(rendered.hcursor.keys.map(_.toVector.sorted),
      Some(Vector("subject", "text", "to")))
    assertEquals(rendered.hcursor.get[String]("to"), Right("alice@example.com"))
    assertEquals(rendered.hcursor.get[String]("subject"),
      Right("Reset your pds.example.com password"))
    val text = rendered.hcursor.get[String]("text").toOption.get
    assert(clue(text).contains("ABC123-DEF456"))
    assert(text.contains("15 minutes"))
    assert(text.contains("pds.example.com"))
  }

  test("every purpose that issues a code renders a distinct subject") {
    val purposes = Vector("sign-in", "confirm-email", "update-email", "reset-password",
      "delete-account", "plc-operation")
    val subjects = purposes.map { purpose =>
      val rendered = Email.render("pds.example.com", payload(purpose, token))
      assert(clue(rendered).isDefined)
      rendered.get.hcursor.get[String]("subject").toOption.get
    }
    assertEquals(subjects.distinct.length, purposes.length)
    assert(subjects.forall(_.contains("pds.example.com")))
  }

  test("an administrative notice carries the operator's own subject and body") {
    val rendered = Email.render("pds.example.com", payload("admin-notice",
      "subject" -> Json.fromString("Scheduled maintenance"),
      "content" -> Json.fromString("The server will restart at 02:00 UTC."))).get
    assertEquals(rendered.hcursor.get[String]("subject"), Right("Scheduled maintenance"))
    assertEquals(rendered.hcursor.get[String]("text"),
      Right("The server will restart at 02:00 UTC."))
  }

  test("a message that cannot be rendered is not sent half-formed") {
    // A code purpose with no code, an unknown purpose, and a notice with no body.
    assertEquals(Email.render("pds.example.com", payload("reset-password")), None)
    assertEquals(Email.render("pds.example.com", payload("something-else", token)), None)
    assertEquals(Email.render("pds.example.com", payload("admin-notice",
      "subject" -> Json.fromString("Only a subject"))), None)
    assertEquals(Email.render("pds.example.com",
      Json.obj("purpose" -> Json.fromString("sign-in"), "token" -> Json.fromString("A"))), None)
  }
