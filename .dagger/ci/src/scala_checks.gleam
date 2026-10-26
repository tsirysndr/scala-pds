import ci
import dagger/client
import dagger/container
import dagger/directory
import dagger/mod
import dagger/mod/codec
import dagger/types

fn builder(context: mod.Context, source: types.Directory) -> types.Container {
  ci.image(context.client, "eclipse-temurin:21-jdk-noble")
  |> ci.commands([
    ["apt-get", "update"],
    [
      "apt-get",
      "install",
      "-y",
      "--no-install-recommends",
      "curl",
      "ca-certificates",
    ],
    [
      "curl",
      "-fsSL",
      "https://github.com/sbt/sbt/releases/download/v1.10.7/sbt-1.10.7.tgz",
      "-o",
      "/tmp/sbt.tgz",
    ],
    ["tar", "-xzf", "/tmp/sbt.tgz", "-C", "/opt"],
  ])
  |> container.with_mounted_cache(
    "/root/.cache/coursier",
    client.cache_volume(context.client, "scala-pds-coursier"),
  )
  |> container.with_mounted_cache(
    "/root/.sbt",
    client.cache_volume(context.client, "scala-pds-sbt-1.10.7"),
  )
  |> container.with_directory("/src", ci.clean(source))
  |> container.with_workdir("/src")
}

fn service(ctr: types.Container) -> types.Service {
  ctr
  |> container.with_env_variable("PDS_HOST", "0.0.0.0")
  |> container.with_env_variable("PDS_PORT", "3000")
  |> container.with_env_variable(
    "PDS_MASTER_KEY",
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  )
  |> container.with_exposed_port(3000)
  |> container.as_service
}

fn smoke(
  context: mod.Context,
  app: types.Service,
) -> Result(String, codec.Error) {
  ci.image(context.client, "curlimages/curl:8.12.1")
  |> container.with_service_binding("pds", app)
  |> ci.commands([
    [
      "curl",
      "--fail",
      "--retry",
      "10",
      "--retry-all-errors",
      "http://pds:3000/xrpc/_health",
    ],
    ["curl", "--fail", "http://pds:3000/xrpc/com.atproto.server.describeServer"],
    ["curl", "--fail", "http://pds:3000/.well-known/oauth-authorization-server"],
    ["curl", "--fail", "http://pds:3000/account"],
  ])
  |> ci.finish
}

pub fn scala(
  context: mod.Context,
  source: types.Directory,
) -> Result(String, codec.Error) {
  let built =
    builder(context, source)
    |> container.with_exec(["/opt/sbt/bin/sbt", "-batch", "test", "assembly"])
  let app =
    ci.image(context.client, "eclipse-temurin:21-jre-noble")
    |> container.with_file(
      "/app.jar",
      container.file(built, "/src/target/scala-3.3.7/scala-pds.jar"),
    )
    |> container.with_default_args(["java", "-jar", "/app.jar"])
    |> service
  smoke(context, app)
}

pub fn image(
  context: mod.Context,
  source: types.Directory,
) -> Result(String, codec.Error) {
  smoke(context, source |> ci.clean |> directory.docker_build |> service)
}
