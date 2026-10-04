package pds.api

import cats.effect.IO
import org.http4s.{Header, Request}
import org.typelevel.ci.CIString

class DelegateHopSuite extends munit.FunSuite:
  private def withHeader(name: String) =
    Request[IO]().putHeaders(Header.Raw(CIString(name), "1"))

  test("a delegate hop is recognised by either header name") {
    // A hop must never resolve outward: in a shared namespace that loops back
    // through the gateway's TLS ask to this server.
    assert(IdentityApi.delegateHop(withHeader("x-pdsgw-delegate-hop")))
    assert(IdentityApi.delegateHop(withHeader("atoll-delegate-hop")))
    assert(!IdentityApi.delegateHop(Request[IO]()))
    assert(!IdentityApi.delegateHop(withHeader("x-forwarded-for")))
  }
