import ci
import dagger/container
import dagger/directory
import dagger/mod
import dagger/mod/codec
import dagger/types

pub fn frontend(
  context: mod.Context,
  source: types.Directory,
) -> Result(String, codec.Error) {
  let built = ci.frontend(context.client, source, "frontend")
  ci.unchanged(
    directory.directory(source, "src/main/resources/ui"),
    container.directory(built, "/src/src/main/resources/ui"),
  )
}
