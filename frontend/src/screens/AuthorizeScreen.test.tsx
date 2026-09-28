import { describe, expect, it, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { App } from "../App";
import { flow, session, stubFetch } from "../test/fixtures";
import * as navigation from "../navigation";

const flowId = "Si5NtpkxOCStLl-unJR-HW_9-kpYy584xfCJp_YNVgA";

function client() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

describe("authorization", () => {
  beforeEach(() => {
    window.history.replaceState({}, "", `/oauth/flow/${flowId}`);
  });

  it("asks for sign-in first, then shows the requested permissions", async () => {
    const calls = stubFetch({
      "/account/session": () => [200, session()],
      [`/oauth/flow/${flowId}/state`]: () => [200, flow()],
      "/account/action/login/password": () => [
        200,
        session({ stage: "authenticated", handle: "alice.pds.example.com" }),
      ],
      [`/oauth/flow/${flowId}/attach`]: () => [
        200,
        flow({ did: "did:web:pds.example.com:u:alice" }),
      ],
    });
    render(<App client={client()} />);

    expect(
      await screen.findByText("https://app.example.com/client-metadata.json"),
    ).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(/Username or email address/), "alice");
    await userEvent.type(screen.getByLabelText(/^Password/), "correct horse battery");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));

    await waitFor(() => expect(screen.getByText("Authorize access")).toBeInTheDocument());
    expect(screen.getByText("Confirm your account identity")).toBeInTheDocument();
    expect(calls.find((call) => call.path === `/oauth/flow/${flowId}/attach`)?.body).toEqual({
      accountCsrf: "a-csrf-token",
    });
  });

  it("navigates to the redirect the server returns when access is allowed", async () => {
    const navigate = vi.spyOn(navigation, "navigate").mockImplementation(() => undefined);
    const calls = stubFetch({
      "/account/session": () => [200, session({ stage: "authenticated", handle: "alice.pds.example.com" })],
      [`/oauth/flow/${flowId}/state`]: () => [200, flow({ did: "did:web:pds.example.com:u:alice" })],
      [`/oauth/flow/${flowId}/decide`]: () => [
        200,
        { location: "https://app.example.com/callback?code=abc&state=xyz" },
      ],
    });
    render(<App client={client()} />);

    await userEvent.click(await screen.findByRole("button", { name: "Allow access" }));

    await waitFor(() =>
      expect(navigate).toHaveBeenCalledWith("https://app.example.com/callback?code=abc&state=xyz"),
    );
    expect(calls.find((call) => call.path === `/oauth/flow/${flowId}/decide`)?.body).toEqual({
      approve: true,
    });
    navigate.mockRestore();
  });

  it("sends a denial when the owner refuses", async () => {
    vi.spyOn(navigation, "navigate").mockImplementation(() => undefined);
    const calls = stubFetch({
      "/account/session": () => [200, session({ stage: "authenticated", handle: "alice.pds.example.com" })],
      [`/oauth/flow/${flowId}/state`]: () => [200, flow({ did: "did:web:pds.example.com:u:alice" })],
      [`/oauth/flow/${flowId}/decide`]: () => [
        200,
        { location: "https://app.example.com/callback?error=access_denied" },
      ],
    });
    render(<App client={client()} />);

    await userEvent.click(await screen.findByRole("button", { name: "Deny" }));

    await waitFor(() =>
      expect(calls.find((call) => call.path === `/oauth/flow/${flowId}/decide`)?.body).toEqual({
        approve: false,
      }),
    );
  });

  it("explains an expired authorization instead of rendering a form", async () => {
    stubFetch({
      "/account/session": () => [200, session()],
      [`/oauth/flow/${flowId}/state`]: () => [
        400,
        { error: "invalid_request", message: "The authorization request has expired" },
      ],
    });
    render(<App client={client()} />);

    expect(await screen.findByText("The authorization request has expired")).toBeInTheDocument();
  });
});
