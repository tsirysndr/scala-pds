# Dagger CI

The pipeline lives in `.dagger/ci/src/dagger_main.gleam` and uses the Gleam SDK vendored in `.dagger/sdk/`. GitHub Actions supplies the checkout and Dagger CLI; containers supply the build tools. No host language toolchain is required.

Use Dagger **v1.0.0-beta.21** with a supported container engine:

```sh
cd .dagger
dagger api call -m ci scala --source ..
dagger api call -m ci image --source ..
dagger api call -m ci frontend --source ..
dagger api call -m ci docs --source ..
dagger api call -m ci nix --source ..
```

Functions return success only after the checks finish. Build, test, and service errors fail the workflow. Node-based tools use **Node 24**, and Bun is pinned to 1.4.2. Image manifests are pinned in `dagger.lock`.

Dagger manages service lifetimes and private networking. Named cache volumes stay inside the Dagger engine; these workflows do not snapshot or upload volumes to GitHub Actions cache. A fresh hosted runner may start with cold caches. Module requests allow up to one hour, with the GitHub job timeout providing the outer limit.

## Editing the pipeline

Keep orchestration in the module language and invoke tools with argument arrays. SDK-generated bindings are ignored under the module directory. Dagger generates them automatically when loading the module. The SDK provenance and licenses are under `.dagger/sdk/`.

For an engine-free syntax/type check against the vendored API snapshot:

```sh
cd .dagger/ci
cp -R ../sdk/gleam dagger_sdk
gleam format --check src
gleam check
```

The Scala check runs tests, builds the assembly, and smoke-tests it as a Dagger service. The image check builds the existing Dockerfile and smoke-tests its service. Frontend checks compare the built bundle with committed assets.

The reference OAuth interoperability harness remains on the runner because it currently depends on loopback URLs and local process control. Native multi-architecture publishing and its pre-tag image smoke checks remain unchanged. No checks were removed from those jobs.
