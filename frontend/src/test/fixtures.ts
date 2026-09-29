import type { Flow, Session } from "../api";

export const session = (overrides: Partial<Session> = {}): Session => ({
  stage: "login",
  csrf: "a-csrf-token",
  origin: "https://pds.example.com",
  "signup-enabled": true,
  "email-enabled": false,
  "invite-required": false,
  "user-domain": "pds.example.com",
  "passkeys-available": true,
  ...overrides,
});

export const flow = (overrides: Partial<Flow> = {}): Flow => ({
  "client-id": "https://app.example.com/client-metadata.json",
  parameters: { scope: "atproto transition:generic" },
  did: null,
  csrf: "a-flow-csrf",
  permissions: ["Confirm your account identity"],
  "permission-sets": [],
  ...overrides,
});

/** Records every fetch and answers from a path-keyed script. */
export function stubFetch(script: Record<string, (body: unknown) => [number, unknown]>) {
  const calls: { path: string; body: unknown }[] = [];
  const fetchMock = async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = typeof input === "string" ? input : input.toString();
    const body = init?.body ? JSON.parse(init.body as string) : undefined;
    calls.push({ path, body });
    const handler = script[path];
    if (!handler) return new Response(JSON.stringify({ error: "NotFound" }), { status: 404 });
    const [status, payload] = handler(body);
    return new Response(JSON.stringify(payload), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  };
  globalThis.fetch = fetchMock as typeof fetch;
  return calls;
}
