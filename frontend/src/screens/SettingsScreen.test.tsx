import { describe, expect, it, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { App } from "../App";
import { session, stubFetch } from "../test/fixtures";

function client() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

const signedIn = session({
  stage: "authenticated",
  handle: "alice.pds.example.com",
  appPasswords: [{ name: "phone", privileged: false, createdAt: "2026-01-01T00:00:00.000Z" }],
  passkeys: [{ id: "credential-id", name: "laptop", createdAt: "2026-01-01T00:00:00.000Z" }],
  oauthSessions: [
    {
      id: "session-id",
      clientId: "https://app.example.com/client-metadata.json",
      scope: "atproto transition:generic",
      permissions: ["Read and write your repository and account data"],
      createdAt: "2026-01-01T00:00:00.000Z",
      expiresAt: "2026-04-01T00:00:00.000Z",
    },
  ],
});

describe("account settings", () => {
  beforeEach(() => {
    window.history.replaceState({}, "", "/account");
  });

  it("lists app passwords and connected applications", async () => {
    stubFetch({ "/account/session": () => [200, signedIn] });
    render(<App client={client()} />);

    expect(await screen.findByText("phone")).toBeInTheDocument();
    expect(
      screen.getByText("Read and write your repository and account data"),
    ).toBeInTheDocument();
  });

  it("shows a new app password once and posts its name", async () => {
    const calls = stubFetch({
      "/account/session": () => [200, signedIn],
      "/account/action/app-passwords/create": () => [
        200,
        { ...signedIn, result: { name: "laptop", password: "abcd-efgh-ijkl-mnop" } },
      ],
    });
    render(<App client={client()} />);

    await userEvent.type(await screen.findByLabelText(/^Name/), "laptop");
    await userEvent.click(screen.getByRole("button", { name: "Create an app password" }));

    expect(await screen.findByText("abcd-efgh-ijkl-mnop")).toBeInTheDocument();
    expect(
      calls.find((call) => call.path === "/account/action/app-passwords/create")?.body,
    ).toEqual({ name: "laptop", privileged: false });
  });

  it("revokes an application", async () => {
    const calls = stubFetch({
      "/account/session": () => [200, signedIn],
      "/account/action/oauth/revoke": () => [200, { ...signedIn, oauthSessions: [] }],
    });
    render(<App client={client()} />);

    await userEvent.click(await screen.findByRole("button", { name: "Revoke access" }));

    await waitFor(() =>
      expect(calls.find((call) => call.path === "/account/action/oauth/revoke")?.body).toEqual({
        id: "session-id",
      }),
    );
  });

  it("refuses a password change whose confirmation does not match", async () => {
    const calls = stubFetch({ "/account/session": () => [200, signedIn] });
    render(<App client={client()} />);

    await userEvent.type(await screen.findByLabelText("Current password"), "old password");
    await userEvent.type(screen.getByLabelText("New password"), "a new password");
    await userEvent.type(screen.getByLabelText("Confirm new password"), "something else");
    await userEvent.click(screen.getByRole("button", { name: "Change password" }));

    expect(await screen.findByText("Those passwords do not match")).toBeInTheDocument();
    expect(calls.filter((call) => call.path === "/account/action/password/change")).toHaveLength(0);
  });

  it("registers a passkey through the browser credential API", async () => {
    const calls = stubFetch({
      "/account/session": () => [200, signedIn],
      "/account/action/passkeys/begin": () => [
        200,
        {
          ...signedIn,
          result: {
            id: "request-id",
            options: {
              publicKey: {
                challenge: "Y2hhbGxlbmdl",
                rp: { id: "pds.example.com", name: "pds.example.com" },
                user: { id: "dXNlcg", name: "alice", displayName: "alice" },
                pubKeyCredParams: [{ type: "public-key", alg: -7 }],
                excludeCredentials: [{ type: "public-key", id: "Y3JlZA" }],
              },
            },
          },
        },
      ],
      "/account/action/passkeys/finish": () => [200, signedIn],
    });

    const created: CredentialCreationOptions[] = [];
    Object.defineProperty(navigator, "credentials", {
      configurable: true,
      value: {
        create: async (options: CredentialCreationOptions) => {
          created.push(options);
          return {
            id: "credential-id",
            rawId: new Uint8Array([1, 2, 3]).buffer,
            type: "public-key",
            getClientExtensionResults: () => ({}),
            response: {
              clientDataJSON: new Uint8Array([4, 5]).buffer,
              attestationObject: new Uint8Array([6, 7]).buffer,
              getTransports: () => ["internal"],
            },
          } as unknown as Credential;
        },
      },
    });

    render(<App client={client()} />);

    await userEvent.type(await screen.findByLabelText("Passkey name"), "this laptop");
    await userEvent.click(screen.getByRole("button", { name: "Add a passkey" }));

    await waitFor(() =>
      expect(calls.some((call) => call.path === "/account/action/passkeys/finish")).toBe(true),
    );
    expect(calls.find((call) => call.path === "/account/action/passkeys/begin")?.body).toEqual({
      name: "this laptop",
    });
    // The challenge and user handle must reach the API as bytes, not base64.
    const options = created[0]?.publicKey as PublicKeyCredentialCreationOptions;
    expect(options.challenge).toBeInstanceOf(Uint8Array);
    expect(options.user.id).toBeInstanceOf(Uint8Array);
    expect(options.excludeCredentials?.[0]?.id).toBeInstanceOf(Uint8Array);

    const finish = calls.find((call) => call.path === "/account/action/passkeys/finish")
      ?.body as { id: string; response: string };
    expect(finish.id).toBe("request-id");
    expect(JSON.parse(finish.response).response.attestationObject).toBe("Bgc");
  });

  it("lists and removes passkeys", async () => {
    const calls = stubFetch({
      "/account/session": () => [200, signedIn],
      "/account/action/passkeys/remove": () => [200, { ...signedIn, passkeys: [] }],
    });
    render(<App client={client()} />);

    expect(await screen.findByText("laptop")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Remove" }));

    await waitFor(() =>
      expect(calls.find((call) => call.path === "/account/action/passkeys/remove")?.body).toEqual({
        id: "credential-id",
      }),
    );
  });

  it("starts authenticator enrollment and confirms it", async () => {
    const calls = stubFetch({
      "/account/session": () => [200, signedIn],
      "/account/action/totp/begin": () => [
        200,
        { ...signedIn, result: { secret: "JBSWY3DPEHPK3PXP", uri: "otpauth://totp/pds.example.com:alice.pds.example.com?secret=JBSWY3DPEHPK3PXP&issuer=pds.example.com" } },
      ],
      "/account/action/totp/confirm": () => [
        200,
        { ...signedIn, factor: "totp", result: { recoveryCodes: ["ABCDEFGHIJKLMNOPQRSTUVWXYZ"] } },
      ],
    });
    render(<App client={client()} />);

    await userEvent.click(await screen.findByRole("button", { name: "Set up an authenticator" }));
    expect(await screen.findByText("JBSWY3DPEHPK3PXP")).toBeInTheDocument();

    // The scannable code and the typed secret are both offered: a camera is not
    // always available, and the QR carries the same enrollment either way.
    const qr = await screen.findByTitle(/authenticator setup code/i);
    expect(qr.closest("svg")).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText("Authenticator code"), "123456");
    await userEvent.click(screen.getByRole("button", { name: "Confirm" }));

    await waitFor(() =>
      expect(screen.getByText("Save these recovery codes now")).toBeInTheDocument(),
    );
    expect(calls.find((call) => call.path === "/account/action/totp/confirm")?.body).toEqual({
      code: "123456",
    });
  });
});
