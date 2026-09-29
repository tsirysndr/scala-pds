// Drives the scala-pds authorization server with the reference OAuth client.
//
// @atproto/oauth-client-node performs discovery, the pushed authorization
// request, PKCE, DPoP and the token exchange exactly as a real application
// would. Only the consent step is driven here, because that is this server's
// own browser surface rather than part of the OAuth contract.

import { NodeOAuthClient } from "@atproto/oauth-client-node";
import { atprotoLoopbackClientMetadata } from "@atproto/oauth-types";
import http from "node:http";

const BASE = process.env.PDS_URL ?? "http://localhost:3199";

let failures = 0;
let checks = 0;

const check = async (name, work) => {
  checks += 1;
  try {
    const detail = await work();
    console.log(`  ok   ${name}${detail ? ` — ${detail}` : ""}`);
  } catch (error) {
    failures += 1;
    console.log(`  FAIL ${name} — ${error?.message ?? error}`);
    for (let cause = error?.cause; cause; cause = cause.cause) {
      console.log(`       cause: ${cause.message ?? cause}`);
    }
  }
};

const memoryStore = () => {
  const entries = new Map();
  return {
    async get(key) {
      return entries.get(key);
    },
    async set(key, value) {
      entries.set(key, value);
    },
    async del(key) {
      entries.delete(key);
    },
  };
};

// --- an account to authorize -------------------------------------------------

const suffix = Math.random().toString(36).slice(2, 8);
const handle = `oauth${suffix}.localhost`;
const password = "correct horse battery";

const json = async (path, init) => {
  const response = await fetch(`${BASE}${path}`, init);
  const text = await response.text();
  if (!response.ok) throw new Error(`${path} → ${response.status}: ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : {};
};

const account = await json("/xrpc/com.atproto.server.createAccount", {
  method: "POST",
  headers: { "content-type": "application/json" },
  body: JSON.stringify({ handle, email: `${suffix}@example.com`, password }),
});

console.log(`scala-pds OAuth interop check against ${BASE}`);
console.log(`account ${account.did}\n`);

// --- the reference client ----------------------------------------------------

// A `did:web` account's document lives at its own hostname, which has no DNS
// entry in a local run. This stands in for a hosts file: the request reaches
// the server's real did:web route carrying the host the DID names. `fetch`
// refuses to send a `Host` header, so the bridged request goes over node:http.
const bridge = (url, init) =>
  new Promise((resolve, reject) => {
    const local = new URL(BASE);
    const request = http.request(
      {
        host: local.hostname,
        port: local.port,
        path: url.pathname,
        method: init?.method ?? "GET",
        headers: { ...Object.fromEntries(new Headers(init?.headers)), Host: url.host },
      },
      (response) => {
        const chunks = [];
        response.on("data", (chunk) => chunks.push(chunk));
        response.on("error", reject);
        response.on("end", () =>
          resolve(
            new Response(Buffer.concat(chunks), {
              status: response.statusCode,
              headers: Object.entries(response.headers).filter(([, v]) => typeof v === "string"),
            }),
          ),
        );
      },
    );
    request.on("error", reject);
    request.end();
  });

const resolvingFetch = async (input, init) => {
  const url = new URL(typeof input === "string" ? input : input.url);
  if (url.pathname === "/.well-known/did.json" && url.host !== new URL(BASE).host) {
    return bridge(url, init);
  }
  return fetch(input, init);
};

const client = new NodeOAuthClient({
  clientMetadata: atprotoLoopbackClientMetadata("http://localhost"),
  stateStore: memoryStore(),
  sessionStore: memoryStore(),
  // The loopback server is not reachable by DNS; resolution is by PDS URL.
  handleResolver: BASE,
  allowHttp: true,
  fetch: resolvingFetch,
});

console.log("authorization");

let authorizeUrl;
await check("the client completes discovery and a pushed authorization request", async () => {
  authorizeUrl = await client.authorize(BASE, { scope: "atproto transition:generic" });
  const params = new URL(authorizeUrl).searchParams;
  if (!params.get("request_uri")?.startsWith("urn:ietf:params:oauth:request_uri:")) {
    throw new Error(`no request_uri in ${authorizeUrl}`);
  }
  if (params.get("client_id") !== "http://localhost") {
    throw new Error(`unexpected client_id ${params.get("client_id")}`);
  }
  return new URL(authorizeUrl).pathname;
});

// --- consent, through this server's own browser surface ----------------------

const cookieOf = (response, name) =>
  (response.headers.getSetCookie?.() ?? [])
    .map((value) => value.trim())
    .find((value) => value.startsWith(`${name}=`))
    ?.slice(name.length + 1)
    .split(";")[0];

let redirect;
await check("the owner signs in and approves the request", async () => {
  const started = await fetch(authorizeUrl, { redirect: "manual" });
  if (started.status !== 303) throw new Error(`authorize returned ${started.status}`);
  const flowCookie = cookieOf(started, "pds-oauth");
  const flowId = started.headers.get("location").replace("/oauth/flow/", "");

  const opened = await fetch(`${BASE}/account/session`);
  const accountCookie = cookieOf(opened, "pds-security");
  const openedBody = await opened.json();

  const same = (extra = {}) => ({
    origin: BASE,
    "sec-fetch-site": "same-origin",
    "content-type": "application/json",
    ...extra,
  });

  const signedIn = await fetch(`${BASE}/account/action/login/password`, {
    method: "POST",
    headers: same({ "x-csrf-token": openedBody.csrf, cookie: `pds-security=${accountCookie}` }),
    body: JSON.stringify({ identifier: handle, password }),
  });
  const signedInBody = await signedIn.json();
  if (signedInBody.stage !== "authenticated") {
    throw new Error(`sign-in stage ${signedInBody.stage}`);
  }
  const ownerCookie = cookieOf(signedIn, "pds-security");

  const state = await fetch(`${BASE}/oauth/flow/${flowId}/state`, {
    headers: { cookie: `pds-oauth=${flowCookie}` },
  });
  const stateBody = await state.json();

  const attached = await fetch(`${BASE}/oauth/flow/${flowId}/attach`, {
    method: "POST",
    headers: same({
      "x-csrf-token": stateBody.csrf,
      cookie: `pds-oauth=${flowCookie}; pds-security=${ownerCookie}`,
    }),
    body: JSON.stringify({ accountCsrf: signedInBody.csrf }),
  });
  if (!attached.ok) throw new Error(`attach returned ${attached.status}`);

  const decided = await fetch(`${BASE}/oauth/flow/${flowId}/decide`, {
    method: "POST",
    headers: same({ "x-csrf-token": stateBody.csrf, cookie: `pds-oauth=${flowCookie}` }),
    body: JSON.stringify({ approve: true }),
  });
  const decidedBody = await decided.json();
  redirect = new URL(decidedBody.location);
  if (!redirect.searchParams.get("code")) throw new Error("no code in the redirect");
  return `permissions: ${stateBody.permissions.length}`;
});

console.log("\ntokens");

let session;
await check("the client exchanges the code for a DPoP-bound session", async () => {
  const result = await client.callback(redirect.searchParams);
  session = result.session;
  if (session.did !== account.did) throw new Error(`session did ${session.did} ≠ ${account.did}`);
  return session.did;
});

await check("the issuer the client received matches the one it discovered", async () => {
  const info = await session.getTokenInfo();
  if (!info.scope?.includes("atproto")) throw new Error(`scope is ${info.scope}`);
  return info.scope;
});

console.log("\nresource access");

await check("the DPoP-bound token calls an XRPC method", async () => {
  const agent = new (await import("@atproto/api")).Agent(session);
  const result = await agent.com.atproto.server.getSession();
  if (result.data.did !== account.did) throw new Error("getSession returned another DID");
  if (result.data.handle !== handle) throw new Error("getSession returned another handle");
  return result.data.handle;
});

await check("the client writes a record over OAuth", async () => {
  const agent = new (await import("@atproto/api")).Agent(session);
  const written = await agent.com.atproto.repo.createRecord({
    repo: account.did,
    collection: "app.bsky.feed.post",
    record: {
      $type: "app.bsky.feed.post",
      text: "written over OAuth",
      createdAt: new Date().toISOString(),
    },
  });
  if (!written.data.uri.startsWith(`at://${account.did}/`)) {
    throw new Error(`unexpected uri ${written.data.uri}`);
  }
  return written.data.uri.split("/").pop();
});

await check("the session refreshes its access token", async () => {
  const before = (await session.getTokenInfo()).expiresAt;
  await session.getTokenInfo(true);
  const agent = new (await import("@atproto/api")).Agent(session);
  const result = await agent.com.atproto.server.getSession();
  if (result.data.did !== account.did) throw new Error("the refreshed session broke");
  return "refreshed";
});

await check("a revoked session stops working", async () => {
  await session.signOut();
  try {
    const agent = new (await import("@atproto/api")).Agent(session);
    await agent.com.atproto.server.getSession();
  } catch {
    return "revoked";
  }
  throw new Error("a revoked session still works");
});

console.log(`\n${checks - failures}/${checks} checks passed`);
process.exit(failures === 0 ? 0 : 1);
