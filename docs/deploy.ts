// Builds the site and publishes it to Deno Deploy as a static application.
//
// Deploy's uploader skips git-ignored paths, and _site is ignored, so the built
// site is staged in a temp directory outside the repository and uploaded from
// there. The application is created on first run: the build configuration has
// to be given explicitly, because detection finds nothing to build in a
// directory that holds only generated files.

const APP = Deno.env.get("DEPLOY_APP") ?? "scala-pds";
const ORG = Deno.env.get("DEPLOY_ORG") ?? "tsirysndr";
const REGION = Deno.env.get("DEPLOY_REGION") ?? "us";
const SITE_URL = Deno.env.get("SITE_URL") ?? `https://${APP}.${ORG}.deno.net/`;

const run = async (cmd: string[], cwd?: string, env?: Record<string, string>) => {
  const { code } = await new Deno.Command(cmd[0], {
    args: cmd.slice(1),
    cwd,
    env,
    stdout: "inherit",
    stderr: "inherit",
  }).output();
  return code;
};

const must = async (cmd: string[], cwd?: string, env?: Record<string, string>) => {
  const code = await run(cmd, cwd, env);
  if (code !== 0) Deno.exit(code);
};

const exists = async () => {
  const { code } = await new Deno.Command("deno", {
    args: ["deploy", "apps", "get", "--json", "-y", "--org", ORG, "--app", APP],
    stdout: "null",
    stderr: "null",
  }).output();
  return code === 0;
};

await must(["deno", "task", "build"], undefined, { ...Deno.env.toObject(), SITE_URL });

const stage = await Deno.makeTempDir({ prefix: `${APP}-` });
await Deno.mkdir(`${stage}/_site`, { recursive: true });
await must(["cp", "-R", "_site/.", `${stage}/_site`]);

const shared = ["--json", "-y", "--org", ORG, "--app", APP];

if (await exists()) {
  await must(["deno", "deploy", "--prod", ...shared, ...Deno.args], stage);
} else {
  await must([
    "deno",
    "deploy",
    "create",
    ...shared,
    "--source",
    "local",
    // Without this, the flags below are ignored in favour of detection.
    "--do-not-use-detected-build-config",
    "--runtime-mode",
    "static",
    "--static-dir",
    "_site",
    "--region",
    REGION,
    ...Deno.args,
  ], stage);
}

await Deno.remove(stage, { recursive: true });
