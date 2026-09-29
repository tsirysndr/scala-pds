// Checks scala-pds output with the reference atproto implementation.
//
// The Scala test suite proves the server agrees with itself. This proves the
// TypeScript packages a relay, AppView or client would actually use accept what
// it produces: the repository CAR, the inclusion proofs, the firehose frames
// and the records, verified by @atproto/repo, @atproto/crypto and
// @atproto/lexicon rather than by anything written here.

import { readdirSync, readFileSync } from "node:fs";
import { WebSocket } from "undici";
import {
  cborToLexRecord,
  readCarWithRoot,
  verifyProofs,
  verifyRecords,
  verifyRepoCar,
} from "@atproto/repo";
import { cborDecodeMulti } from "@atproto/common";
import { parseDidKey, verifySignature } from "@atproto/crypto";
import { Lexicons } from "@atproto/lexicon";
import { AtpAgent } from "@atproto/api";
import { CID } from "multiformats/cid";

const BASE = process.env.PDS_URL ?? "http://127.0.0.1:3199";
const LEXICONS = new URL("../src/main/resources/lexicons/", import.meta.url);

let failures = 0;
let checks = 0;

const ok = (name, detail = "") => {
  checks += 1;
  console.log(`  ok   ${name}${detail ? ` — ${detail}` : ""}`);
};

const fail = (name, error) => {
  checks += 1;
  failures += 1;
  console.log(`  FAIL ${name} — ${error?.message ?? error}`);
};

const check = async (name, work) => {
  try {
    const detail = await work();
    ok(name, detail);
  } catch (error) {
    fail(name, error);
  }
};

const call = async (path, init) => {
  const response = await fetch(`${BASE}${path}`, init);
  const text = await response.text();
  if (!response.ok) throw new Error(`${path} → ${response.status}: ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : {};
};

const post = (path, body, token) =>
  call(path, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  });

const bytes = async (path, token) => {
  const response = await fetch(`${BASE}${path}`, {
    headers: token ? { authorization: `Bearer ${token}` } : {},
  });
  if (!response.ok) throw new Error(`${path} → ${response.status}`);
  return new Uint8Array(await response.arrayBuffer());
};

// --- fixtures ---------------------------------------------------------------

const suffix = Math.random().toString(36).slice(2, 8);
const handle = `interop${suffix}.localhost`;
const password = "correct horse battery";

const records = [
  {
    collection: "app.bsky.feed.post",
    rkey: null,
    value: {
      $type: "app.bsky.feed.post",
      text: "hello from scala-pds",
      createdAt: new Date().toISOString(),
      langs: ["en"],
    },
  },
  {
    collection: "app.bsky.feed.post",
    rkey: null,
    value: {
      $type: "app.bsky.feed.post",
      text: "a reply with facets",
      createdAt: new Date().toISOString(),
      facets: [
        {
          index: { byteStart: 0, byteEnd: 1 },
          features: [{ $type: "app.bsky.richtext.facet#tag", tag: "interop" }],
        },
      ],
    },
  },
  {
    collection: "app.bsky.graph.follow",
    rkey: null,
    value: {
      $type: "app.bsky.graph.follow",
      subject: "did:plc:z72i7hdynmk6r22z27h6tvur",
      createdAt: new Date().toISOString(),
    },
  },
  {
    collection: "app.bsky.actor.profile",
    rkey: "self",
    value: {
      $type: "app.bsky.actor.profile",
      displayName: "Interop",
      description: "Checked by the reference implementation.",
    },
  },
];

// --- run --------------------------------------------------------------------

console.log(`scala-pds interop check against ${BASE}\n`);

const describe = await call("/xrpc/com.atproto.server.describeServer");
console.log(`server did ${describe.did}, handle domains ${describe.availableUserDomains}\n`);

const account = await post("/xrpc/com.atproto.server.createAccount", {
  handle,
  email: `${suffix}@example.com`,
  password,
});
const { did, accessJwt: token } = account;
console.log(`account ${did}\n`);

const written = [];
for (const record of records) {
  const result = await post(
    "/xrpc/com.atproto.repo.createRecord",
    { repo: did, collection: record.collection, ...(record.rkey ? { rkey: record.rkey } : {}), record: record.value },
    token,
  );
  written.push({ ...record, uri: result.uri, cid: result.cid });
}
console.log(`wrote ${written.length} records\n`);

console.log("repository");

const didDoc = (await call(`/xrpc/com.atproto.repo.describeRepo?repo=${encodeURIComponent(did)}`))
  .didDoc;
let signingKey;
await check("the DID document's verification method parses as a did:key", () => {
  signingKey = didDoc.verificationMethod[0].publicKeyMultibase;
  const didKey = didDoc.verificationMethod[0].id;
  const parsed = parseDidKey(`did:key:${signingKey}`);
  return `${parsed.jwtAlg}, ${didKey}`;
});
const didKey = `did:key:${signingKey}`;

const car = await bytes(`/xrpc/com.atproto.sync.getRepo?did=${encodeURIComponent(did)}`);

let verified;
await check("@atproto/repo verifies the exported repository", async () => {
  verified = await verifyRepoCar(car, did, didKey);
  return `${verified.creates.length} records, ${car.length} bytes`;
});

await check("every written record is present with the CID the server reported", () => {
  const byUri = new Map(
    verified.creates.map((create) => [`at://${did}/${create.collection}/${create.rkey}`, create]),
  );
  for (const record of written) {
    const found = byUri.get(record.uri);
    if (!found) throw new Error(`${record.uri} missing from the verified repository`);
    if (found.cid.toString() !== record.cid) {
      throw new Error(`${record.uri} cid ${found.cid} ≠ ${record.cid}`);
    }
  }
  return `${written.length} matched`;
});

await check("a repository signed by another key is rejected", async () => {
  const other = "did:key:zQ3shokFTS3brHcDQrn82RUDfCZESWL1ZdCEJwekUDPQiYBme";
  try {
    await verifyRepoCar(car, did, other);
  } catch {
    return "rejected";
  }
  throw new Error("a foreign signing key was accepted");
});

await check("a tampered archive is rejected", async () => {
  const broken = car.slice();
  broken[broken.length - 1] ^= 1;
  try {
    await verifyRepoCar(broken, did, didKey);
  } catch {
    return "rejected";
  }
  throw new Error("a tampered archive was accepted");
});

console.log("\nproofs");

const target = written[0];
const [collection, rkey] = target.uri.split("/").slice(-2);
const proof = await bytes(
  `/xrpc/com.atproto.sync.getRecord?did=${encodeURIComponent(did)}` +
    `&collection=${collection}&rkey=${rkey}`,
);

await check("@atproto/repo verifies the inclusion proof", async () => {
  const claims = [{ collection, rkey, cid: CID.parse(target.cid) }];
  const result = await verifyProofs(proof, claims, did, didKey);
  if (result.verified.length !== 1 || result.unverified.length !== 0) {
    throw new Error(`verified ${result.verified.length}, unverified ${result.unverified.length}`);
  }
  return "1 claim";
});

await check("the proof carries the record itself", async () => {
  const claims = await verifyRecords(proof, did, didKey);
  const found = claims.find((claim) => claim.rkey === rkey);
  if (!found?.record) throw new Error("the record was not in the proof");
  if (found.record.text !== target.value.text) throw new Error("the record content differs");
  return found.record.text;
});

await check("an exclusion proof verifies for an absent record", async () => {
  const absent = await bytes(
    `/xrpc/com.atproto.sync.getRecord?did=${encodeURIComponent(did)}` +
      `&collection=${collection}&rkey=3zzzzzzzzzzzz`,
  );
  const claims = [{ collection, rkey: "3zzzzzzzzzzzz", cid: null }];
  const result = await verifyProofs(absent, claims, did, didKey);
  if (result.verified.length !== 1) throw new Error("absence was not proven");
  return "absence proven";
});

console.log("\nlexicons");

const lexicons = new Lexicons();
let loaded = 0;
for (const file of readdirSync(LEXICONS)) {
  if (!file.endsWith(".json") || file === "index.json") continue;
  try {
    lexicons.add(JSON.parse(readFileSync(new URL(file, LEXICONS), "utf8")));
    loaded += 1;
  } catch {
    // Not every vendored document is a schema the validator accepts standalone.
  }
}

// verifyRepoCar reports which records exist, not their contents, so the bytes
// are decoded from the archive with the reference decoder before validating.
const carBlocks = (await readCarWithRoot(car)).blocks;

await check("the reference DAG-CBOR decoder reads every record the server wrote", () => {
  for (const create of verified.creates) {
    const block = carBlocks.get(create.cid);
    if (!block) throw new Error(`${create.collection}/${create.rkey}: block missing from the CAR`);
    const record = cborToLexRecord(block);
    if (!record || typeof record !== "object") {
      throw new Error(`${create.collection}/${create.rkey}: decoded to ${typeof record}`);
    }
    if (record.$type !== create.collection) {
      throw new Error(`${create.collection}/${create.rkey}: $type is ${record.$type}`);
    }
  }
  return `${verified.creates.length} records`;
});

await check("@atproto/lexicon accepts every record the server stored", () => {
  if (loaded === 0) throw new Error("no lexicon documents loaded");
  for (const create of verified.creates) {
    const record = cborToLexRecord(carBlocks.get(create.cid));
    const result = lexicons.validate(create.collection, record);
    if (!result.success) {
      throw new Error(
        `${create.collection}/${create.rkey}: ${result.error.message}` +
          ` (${JSON.stringify(record)?.slice(0, 160)})`,
      );
    }
  }
  return `${verified.creates.length} records against ${loaded} schemas`;
});

await check("the server refuses what @atproto/lexicon refuses", async () => {
  const bad = {
    $type: "app.bsky.feed.post",
    text: 3,
    createdAt: new Date().toISOString(),
  };
  const reference = lexicons.validate("app.bsky.feed.post", bad);
  if (reference.success) throw new Error("the reference accepted an invalid record");
  try {
    await post(
      "/xrpc/com.atproto.repo.createRecord",
      { repo: did, collection: "app.bsky.feed.post", record: bad },
      token,
    );
  } catch (error) {
    if (!error.message.includes("InvalidRecord")) throw error;
    return "both rejected";
  }
  throw new Error("the server accepted a record the reference rejects");
});

console.log("\nofficial client");

// @atproto/api is what a real client runs: it builds the requests and
// validates the responses against the published lexicons, so a wire-format
// mismatch surfaces here rather than as a silent difference.
const agent = new AtpAgent({ service: BASE });

await check("@atproto/api signs in with a password", async () => {
  await agent.login({ identifier: handle, password });
  if (agent.session?.did !== did) throw new Error(`session did ${agent.session?.did} ≠ ${did}`);
  return `${agent.session.handle}, active=${agent.session.active}`;
});

await check("@atproto/api reads the session back", async () => {
  const session = await agent.com.atproto.server.getSession();
  if (session.data.did !== did) throw new Error("getSession returned another DID");
  if (session.data.handle !== handle) throw new Error("getSession returned another handle");
  return session.data.handle;
});

let clientRecord;
await check("@atproto/api writes and reads a record", async () => {
  const written = await agent.com.atproto.repo.createRecord({
    repo: did,
    collection: "app.bsky.feed.post",
    record: {
      $type: "app.bsky.feed.post",
      text: "written by the official client",
      createdAt: new Date().toISOString(),
    },
  });
  clientRecord = written.data;
  const read = await agent.com.atproto.repo.getRecord({
    repo: did,
    collection: "app.bsky.feed.post",
    rkey: written.data.uri.split("/").pop(),
  });
  if (read.data.cid !== written.data.cid) throw new Error("the record CID changed on read");
  if (read.data.value.text !== "written by the official client") {
    throw new Error("the record content differs");
  }
  return written.data.uri;
});

await check("@atproto/api lists records and describes the repository", async () => {
  const listed = await agent.com.atproto.repo.listRecords({
    repo: did,
    collection: "app.bsky.feed.post",
  });
  if (listed.data.records.length < 3) {
    throw new Error(`expected at least 3 posts, got ${listed.data.records.length}`);
  }
  const described = await agent.com.atproto.repo.describeRepo({ repo: did });
  if (described.data.did !== did) throw new Error("describeRepo returned another DID");
  if (!described.data.collections.includes("app.bsky.feed.post")) {
    throw new Error("describeRepo omitted a collection in use");
  }
  return `${listed.data.records.length} posts, ${described.data.collections.length} collections`;
});

await check("@atproto/api rotates the session with a refresh token", async () => {
  const before = agent.session?.accessJwt;
  const refreshed = await agent.com.atproto.server.refreshSession(undefined, {
    headers: { authorization: `Bearer ${agent.session.refreshJwt}` },
  });
  if (refreshed.data.did !== did) throw new Error("refreshSession returned another DID");
  if (refreshed.data.accessJwt === before) throw new Error("the access token did not change");
  return "rotated";
});

await check("@atproto/api deletes the record it wrote", async () => {
  await agent.com.atproto.repo.deleteRecord({
    repo: did,
    collection: "app.bsky.feed.post",
    rkey: clientRecord.uri.split("/").pop(),
  });
  try {
    await agent.com.atproto.repo.getRecord({
      repo: did,
      collection: "app.bsky.feed.post",
      rkey: clientRecord.uri.split("/").pop(),
    });
  } catch (error) {
    return "deleted";
  }
  throw new Error("the deleted record is still readable");
});

await check("@atproto/api reads the server description", async () => {
  const described = await agent.com.atproto.server.describeServer();
  if (described.data.did !== describe.did) throw new Error("describeServer disagrees");
  if (!Array.isArray(described.data.availableUserDomains)) {
    throw new Error("availableUserDomains is not a list");
  }
  return described.data.did;
});

console.log("\nfirehose");

await check("@atproto/repo verifies the blocks in a #commit frame", async () => {
  const frames = await collectFrames(
    `${BASE.replace("http", "ws")}/xrpc/com.atproto.sync.subscribeRepos?cursor=0`,
    6,
  );
  const commit = frames.find(
    (frame) => frame.header.t === "#commit" && frame.body.repo === did,
  );
  if (!commit) {
    throw new Error(`no #commit frame for ${did} in ${frames.map((f) => f.header.t)}`);
  }

  const { root, blocks } = await readCarWithRoot(commit.body.blocks);
  if (root.toString() !== commit.body.commit.toString()) {
    throw new Error("the frame's CAR root is not the commit it announces");
  }
  const { verifyCommitSig } = await import("@atproto/repo");
  const commitBlock = blocks.get(root);
  const { cborDecode } = await import("@atproto/common");
  const decoded = cborDecode(commitBlock);
  const valid = await verifyCommitSig(decoded, didKey);
  if (!valid) {
    throw new Error(
      `the commit signature does not verify: commit.did=${decoded.did} expected=${did}` +
        ` rev=${decoded.rev} didKey=${didKey} sigBytes=${decoded.sig?.length}` +
        ` frames=${frames.map((f) => f.header.t).join(",")}`,
    );
  }
  if (decoded.did !== did) throw new Error("the commit is for another repository");
  return `${frames.length} frames, ${blocks.size} blocks in the commit`;
});

await check("the frame sequence starts with identity, account and sync", async () => {
  const frames = await collectFrames(
    `${BASE.replace("http", "ws")}/xrpc/com.atproto.sync.subscribeRepos?cursor=0`,
    4,
  );
  const kinds = frames.filter((frame) => frame.body.did === did || frame.body.repo === did)
    .map((frame) => frame.header.t);
  const expected = ["#identity", "#account", "#sync"];
  for (let i = 0; i < expected.length; i += 1) {
    if (kinds[i] !== expected[i]) throw new Error(`frame ${i} is ${kinds[i]}, expected ${expected[i]}`);
  }
  if (typeof frames[0].body.seq !== "number") throw new Error("frames carry no sequence number");
  return kinds.join(" ");
});

async function collectFrames(url, count) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    const frames = [];
    const timer = setTimeout(() => {
      socket.close();
      frames.length ? resolve(frames) : reject(new Error("no frames within the timeout"));
    }, 8000);
    socket.binaryType = "arraybuffer";
    socket.onerror = (event) => {
      clearTimeout(timer);
      reject(new Error(`websocket error: ${event.message ?? event.error ?? "unknown"}`));
    };
    socket.onmessage = (event) => {
      const [header, body] = cborDecodeMulti(new Uint8Array(event.data));
      frames.push({ header, body });
      if (frames.length >= count) {
        clearTimeout(timer);
        socket.close();
        resolve(frames);
      }
    };
  });
}

console.log(`\n${checks - failures}/${checks} checks passed`);
process.exit(failures === 0 ? 0 : 1);
