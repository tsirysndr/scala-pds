import dagger/directory
import dagger/mod
import dagger/mod/argument
import dagger/mod/codec
import docs_checks
import frontend_checks
import nix_checks
import scala_checks

pub fn definition() -> mod.Object {
  let source = argument.required("source", directory.codec())
  let root =
    mod.object("ProjectCi")
    |> mod.with_function(mod.function(
      "scala",
      source,
      codec.string(),
      scala_checks.scala,
    ))
    |> mod.with_function(mod.function(
      "image",
      source,
      codec.string(),
      scala_checks.image,
    ))
    |> mod.with_function(mod.function(
      "frontend",
      source,
      codec.string(),
      frontend_checks.frontend,
    ))
    |> mod.with_function(mod.function(
      "docs",
      source,
      codec.string(),
      docs_checks.docs,
    ))
    |> mod.with_function(mod.function(
      "nix",
      source,
      codec.string(),
      nix_checks.nix,
    ))
  root
}
