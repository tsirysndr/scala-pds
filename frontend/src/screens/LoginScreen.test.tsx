import { describe, expect, it, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { App } from "../App";
import { session, stubFetch } from "../test/fixtures";

function client() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

describe("sign-in", () => {
  beforeEach(() => {
    window.history.replaceState({}, "", "/account");
  });

  it("validates the form before posting anything", async () => {
    const calls = stubFetch({ "/account/session": () => [200, session()] });
    render(<App client={client()} />);

    await userEvent.click(await screen.findByRole("button", { name: "Sign in" }));

    expect(await screen.findByText("Enter your password")).toBeInTheDocument();
    expect(calls.filter((call) => call.path.startsWith("/account/action"))).toHaveLength(0);
  });

  it("posts the identifier and password with the CSRF token", async () => {
    const calls = stubFetch({
      "/account/session": () => [200, session()],
      "/account/action/login/password": () => [
        200,
        session({ stage: "authenticated", handle: "alice.pds.example.com" }),
      ],
    });
    render(<App client={client()} />);

    await userEvent.type(
      await screen.findByLabelText(/Username or email address/),
      "alice.pds.example.com",
    );
    await userEvent.type(screen.getByLabelText(/^Password/), "correct horse battery");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));

    await waitFor(() => expect(screen.getByText("Your account")).toBeInTheDocument());
    const login = calls.find((call) => call.path === "/account/action/login/password");
    expect(login?.body).toEqual({
      identifier: "alice.pds.example.com",
      password: "correct horse battery",
    });
  });

  it("shows a readable message when the server rejects the credentials", async () => {
    stubFetch({
      "/account/session": () => [200, session()],
      "/account/action/login/password": () => [
        401,
        { error: "AuthenticationRequired", stage: "login", csrf: "a-csrf-token" },
      ],
    });
    render(<App client={client()} />);

    await userEvent.type(await screen.findByLabelText(/Username or email address/), "alice");
    await userEvent.type(screen.getByLabelText(/^Password/), "wrong password");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(
      await screen.findByText("Sign-in failed. Check your identifier and password."),
    ).toBeInTheDocument();
  });

  it("signs in with a passkey when the browser provides one", async () => {
    const calls = stubFetch({
      "/account/session": () => [200, session()],
      "/account/action/login/passkey/begin": () => [
        200,
        {
          ...session(),
          result: {
            id: "request-id",
            options: {
              publicKey: {
                challenge: "Y2hhbGxlbmdl",
                rpId: "pds.example.com",
                allowCredentials: [{ type: "public-key", id: "Y3JlZA" }],
              },
            },
          },
        },
      ],
      "/account/action/login/passkey/finish": () => [
        200,
        session({ stage: "authenticated", handle: "alice.pds.example.com" }),
      ],
    });

    const requested: CredentialRequestOptions[] = [];
    Object.defineProperty(navigator, "credentials", {
      configurable: true,
      value: {
        get: async (options: CredentialRequestOptions) => {
          requested.push(options);
          return {
            id: "credential-id",
            rawId: new Uint8Array([1]).buffer,
            type: "public-key",
            getClientExtensionResults: () => ({}),
            response: {
              clientDataJSON: new Uint8Array([2]).buffer,
              authenticatorData: new Uint8Array([3]).buffer,
              signature: new Uint8Array([4]).buffer,
              userHandle: new Uint8Array([5]).buffer,
            },
          } as unknown as Credential;
        },
      },
    });

    render(<App client={client()} />);

    await userEvent.type(
      await screen.findByLabelText(/Username or email address/),
      "alice.pds.example.com",
    );
    await userEvent.click(screen.getByRole("button", { name: "Sign in with a passkey" }));

    await waitFor(() => expect(screen.getByText("Your account")).toBeInTheDocument());
    expect(
      calls.find((call) => call.path === "/account/action/login/passkey/begin")?.body,
    ).toEqual({ identifier: "alice.pds.example.com" });
    const options = requested[0]?.publicKey as PublicKeyCredentialRequestOptions;
    expect(options.challenge).toBeInstanceOf(Uint8Array);
    expect(options.allowCredentials?.[0]?.id).toBeInstanceOf(Uint8Array);
  });

  it("asks for an identifier before starting a passkey ceremony", async () => {
    const calls = stubFetch({ "/account/session": () => [200, session()] });
    render(<App client={client()} />);

    await userEvent.click(
      await screen.findByRole("button", { name: "Sign in with a passkey" }),
    );

    expect(
      await screen.findByText("Enter your username or email address first"),
    ).toBeInTheDocument();
    expect(calls.filter((call) => call.path.includes("passkey"))).toHaveLength(0);
  });

  it("offers signup only when the server allows it", async () => {
    stubFetch({ "/account/session": () => [200, session({ "signup-enabled": false })] });
    render(<App client={client()} />);

    await screen.findByRole("button", { name: "Sign in" });
    expect(screen.queryByRole("button", { name: "Create one" })).toBeNull();
  });
});
