package pds

class BuildSuite extends munit.CatsEffectSuite:
  test("Cats Effect runtime executes effects") {
    cats.effect.IO.pure(42).map(result => assertEquals(result, 42))
  }
