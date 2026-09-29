import { describe, expect, it, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { App } from "../App";
import { session, stubFetch } from "../test/fixtures";

function client() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

describe("signup", () => {
  beforeEach(() => {
    window.history.replaceState({}, "", "/account");
  });

  it("builds the handle from the username and the server domain", async () => {
    const calls = stubFetch({
      "/account/session": () => [200, session()],
      "/account/action/signup": () => [
        200,
        session({ stage: "authenticated", handle: "alice.pds.example.com" }),
      ],
    });
    render(<App client={client()} />);

    await userEvent.click(await screen.findByRole("button", { name: "Create one" }));
    await userEvent.type(screen.getByLabelText(/Username/), "Alice");
    await userEvent.type(screen.getByLabelText(/Email address/), "alice@example.com");
    await userEvent.type(screen.getByLabelText(/^Password/), "correct horse battery");
    await userEvent.type(screen.getByLabelText(/Confirm password/), "correct horse battery");
    await userEvent.click(screen.getByRole("button", { name: "Create account" }));

    await waitFor(() =>
      expect(calls.some((call) => call.path === "/account/action/signup")).toBe(true),
    );
    expect(calls.find((call) => call.path === "/account/action/signup")?.body).toEqual({
      handle: "alice.pds.example.com",
      email: "alice@example.com",
      password: "correct horse battery",
    });
  });

  it("shows the server domain beside the field and previews the handle as you type", async () => {
    stubFetch({ "/account/session": () => [200, session()] });
    render(<App client={client()} />);

    await userEvent.click(await screen.findByRole("button", { name: "Create one" }));

    expect(screen.getByText(".pds.example.com")).toBeInTheDocument();
    expect(screen.getByText(/Your handle will be username\.pds\.example\.com/i)).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText(/Username/), "Carol");

    expect(screen.getByText(/Your handle will be carol\.pds\.example\.com/i)).toBeInTheDocument();
  });

  it("rejects short usernames, bad emails and weak passwords before posting", async () => {
    const calls = stubFetch({ "/account/session": () => [200, session()] });
    render(<App client={client()} />);

    await userEvent.click(await screen.findByRole("button", { name: "Create one" }));
    await userEvent.type(screen.getByLabelText(/Username/), "ab");
    await userEvent.type(screen.getByLabelText(/Email address/), "not-an-email");
    await userEvent.type(screen.getByLabelText(/^Password/), "short");
    await userEvent.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByText("Usernames need at least three characters")).toBeInTheDocument();
    expect(screen.getByText("Enter a valid email address")).toBeInTheDocument();
    expect(screen.getByText("Passwords need at least 8 characters")).toBeInTheDocument();
    expect(calls.filter((call) => call.path === "/account/action/signup")).toHaveLength(0);
  });

  it("requires an invite code when the server does", async () => {
    stubFetch({ "/account/session": () => [200, session({ "invite-required": true })] });
    render(<App client={client()} />);

    await userEvent.click(await screen.findByRole("button", { name: "Create one" }));
    expect(screen.getByLabelText(/Invite code/)).toBeRequired();
  });

  it("catches a blank invite code in the browser when one is required", async () => {
    const calls = stubFetch({
      "/account/session": () => [200, session({ "invite-required": true })],
    });
    render(<App client={client()} />);

    await userEvent.click(await screen.findByRole("button", { name: "Create one" }));
    await userEvent.type(screen.getByLabelText(/Username/), "alice");
    await userEvent.type(screen.getByLabelText(/Email address/), "alice@example.com");
    await userEvent.type(screen.getByLabelText(/^Password/), "correct horse battery");
    await userEvent.type(screen.getByLabelText(/Confirm password/), "correct horse battery");
    await userEvent.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByText("Enter your invite code")).toBeInTheDocument();
    expect(calls.filter((call) => call.path === "/account/action/signup")).toHaveLength(0);
  });

  it("submits the invite code the visitor typed", async () => {
    const calls = stubFetch({
      "/account/session": () => [200, session({ "invite-required": true })],
      "/account/action/signup": () => [200, session({ stage: "authenticated" })],
    });
    render(<App client={client()} />);

    await userEvent.click(await screen.findByRole("button", { name: "Create one" }));
    await userEvent.type(screen.getByLabelText(/Username/), "alice");
    await userEvent.type(screen.getByLabelText(/Email address/), "alice@example.com");
    await userEvent.type(screen.getByLabelText(/^Password/), "correct horse battery");
    await userEvent.type(screen.getByLabelText(/Confirm password/), "correct horse battery");
    await userEvent.type(screen.getByLabelText(/Invite code/), "pds-example-com-abc12");
    await userEvent.click(screen.getByRole("button", { name: "Create account" }));

    await waitFor(() =>
      expect(calls.some((call) => call.path === "/account/action/signup")).toBe(true),
    );
    expect(calls.find((call) => call.path === "/account/action/signup")?.body).toMatchObject({
      inviteCode: "pds-example-com-abc12",
    });
  });

  it("asks for no invite code when the server does not require one", async () => {
    // The server ignores a supplied code unless it requires one.
    stubFetch({ "/account/session": () => [200, session({ "invite-required": false })] });
    render(<App client={client()} />);

    await userEvent.click(await screen.findByRole("button", { name: "Create one" }));
    expect(await screen.findByLabelText(/Username/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/Invite code/)).not.toBeInTheDocument();
    expect(screen.queryByPlaceholderText("Invite code")).not.toBeInTheDocument();
  });
  it("refuses a confirmation that does not match", async () => {
    const calls = stubFetch({ "/account/session": () => [200, session()] });
    render(<App client={client()} />);

    await userEvent.click(await screen.findByRole("button", { name: "Create one" }));
    await userEvent.type(screen.getByLabelText(/Username/), "alice");
    await userEvent.type(screen.getByLabelText(/Email address/), "alice@example.com");
    await userEvent.type(screen.getByLabelText(/^Password/), "correct horse battery");
    await userEvent.type(screen.getByLabelText(/Confirm password/), "correct horse bat");
    await userEvent.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByText("Those passwords do not match")).toBeInTheDocument();
    expect(calls.filter((call) => call.path === "/account/action/signup")).toHaveLength(0);
  });

  it("never sends the confirmation to the server", async () => {
    const calls = stubFetch({
      "/account/session": () => [200, session()],
      "/account/action/signup": () => [200, session({ stage: "authenticated" })],
    });
    render(<App client={client()} />);

    await userEvent.click(await screen.findByRole("button", { name: "Create one" }));
    await userEvent.type(screen.getByLabelText(/Username/), "alice");
    await userEvent.type(screen.getByLabelText(/Email address/), "alice@example.com");
    await userEvent.type(screen.getByLabelText(/^Password/), "correct horse battery");
    await userEvent.type(screen.getByLabelText(/Confirm password/), "correct horse battery");
    await userEvent.click(screen.getByRole("button", { name: "Create account" }));

    await waitFor(() =>
      expect(calls.some((call) => call.path === "/account/action/signup")).toBe(true),
    );
    const body = calls.find((call) => call.path === "/account/action/signup")?.body as
      | Record<string, unknown>
      | undefined;
    expect(body).toBeDefined();
    expect(Object.keys(body ?? {})).not.toContain("confirmPassword");
  });
});
