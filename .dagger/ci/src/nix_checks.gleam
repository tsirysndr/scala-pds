import ci
import dagger/mod
import dagger/mod/codec
import dagger/types

pub fn nix(
  context: mod.Context,
  source: types.Directory,
) -> Result(String, codec.Error) {
  ci.nix(context.client, source, "scala-pds")
  |> ci.commands([
    ["nix", "flake", "check", "--all-systems", "--no-build"],
    ["nix", "build", ".#scala-pds"],
  ])
  |> ci.finish
}
