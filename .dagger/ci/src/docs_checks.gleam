import ci
import dagger/container
import dagger/mod
import dagger/mod/codec
import dagger/types

pub fn docs(
  context: mod.Context,
  source: types.Directory,
) -> Result(String, codec.Error) {
  ci.image(context.client, "denoland/deno:2.5.6")
  |> container.with_directory("/src", ci.clean(source))
  |> container.with_workdir("/src/docs")
  |> container.with_exec(["deno", "task", "build"])
  |> ci.finish
}
