import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

export type AppPassword = { name: string; privileged: boolean; createdAt: string };

export type OAuthSession = {
  id: string;
  clientId: string;
  scope: string;
  permissions: string[];
  createdAt: string;
  expiresAt: string;
};

export type Stage = "login" | "factor" | "authenticated";

export type Session = {
  stage: Stage;
  csrf: string;
  handle?: string;
  factor?: "totp" | "email" | null;
  appPasswords?: AppPassword[];
  recoveryCodes?: number;
  oauthSessions?: OAuthSession[];
  origin: string;
  "signup-enabled": boolean;
  "email-enabled": boolean;
  "invite-required": boolean;
  "user-domain": string;
  "passkeys-available": boolean;
};

export type PermissionSet = { nsid?: string; title?: string; permissions: string[] };

export type FlowParameters = {
  scope?: string;
  login_hint?: string;
  prompt?: string;
  [key: string]: unknown;
};

export type Flow = {
  "client-id": string;
  parameters: FlowParameters;
  did: string | null;
  csrf: string;
  permissions: string[];
  "permission-sets": PermissionSet[];
};

export const messages: Record<string, string> = {
  AuthenticationRequired: "Sign-in failed. Check your identifier and password.",
  BrowserSessionRequired: "Your session expired. Sign in again to continue.",
  InvalidCsrf: "This page is stale. Reload it and try again.",
  InvalidToken: "That code is not valid. Check it and try again.",
  RateLimitExceeded: "Too many attempts. Wait a moment and try again.",
  AlreadySignedIn: "Sign out before choosing another account.",
  HandleNotAvailable: "That username, email, or account already exists.",
  InvalidInviteCode: "That invite code cannot be used.",
  InvalidHandle: "That username cannot be used on this server.",
  InvalidPassword: "Passwords need at least 8 characters.",
  InvalidEmail: "That email address cannot be used.",
  EmailUnavailable: "Email delivery is not configured on this server.",
  RegistrationPending: "Your registration is still being confirmed. Try signing in shortly.",
  TotpAlreadyEnabled: "An authenticator is already enabled.",
  AccountTakedown: "This account has been suspended.",
  AccountDeactivated: "This account is deactivated.",
  Forbidden: "This server does not allow that.",
  InternalServerError: "Something went wrong. Try again.",
};

export class ApiError extends Error {
  code: string;
  constructor(code: string, message: string) {
    super(message);
    this.code = code;
  }
}

function friendly(code: string | undefined, fallback?: string): ApiError {
  const key = code ?? "InternalServerError";
  return new ApiError(key, messages[key] ?? fallback ?? "Something went wrong. Try again.");
}

export function currentFlowId(): string | null {
  return /^\/oauth\/flow\/([A-Za-z0-9_-]{43})$/.exec(window.location.pathname)?.[1] ?? null;
}

async function parse(response: Response): Promise<Record<string, unknown>> {
  try {
    return (await response.json()) as Record<string, unknown>;
  } catch {
    return {};
  }
}

export function useSession() {
  return useQuery({
    queryKey: ["session"],
    retry: false,
    staleTime: Infinity,
    queryFn: async (): Promise<Session> => {
      const response = await fetch("/account/session", {
        credentials: "same-origin",
        cache: "no-store",
      });
      const data = await parse(response);
      if (!response.ok) throw friendly(data.error as string, data.message as string);
      return data as unknown as Session;
    },
  });
}

export function useFlow() {
  const flowId = currentFlowId();
  return useQuery({
    queryKey: ["flow", flowId],
    enabled: flowId !== null,
    retry: false,
    staleTime: Infinity,
    queryFn: async (): Promise<Flow> => {
      const response = await fetch(`/oauth/flow/${flowId}/state`, {
        credentials: "same-origin",
        cache: "no-store",
      });
      const data = await parse(response);
      if (!response.ok)
        throw friendly(
          data.error as string,
          (data.message as string) ?? "Restart authorization from your application.",
        );
      return data as unknown as Flow;
    },
  });
}

/** POST an account action; the response body always carries the fresh view. */
export function useAction() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ action, body }: { action: string; body?: Record<string, unknown> }) => {
      const session = queryClient.getQueryData<Session>(["session"]);
      const response = await fetch(`/account/action/${action}`, {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json", "X-CSRF-Token": session?.csrf ?? "" },
        body: JSON.stringify(body ?? {}),
      });
      const data = await parse(response);
      if (typeof data.stage === "string")
        queryClient.setQueryData(["session"], data as unknown as Session);
      if (!response.ok) throw friendly(data.error as string, data.message as string);
      return (data.result ?? {}) as Record<string, unknown>;
    },
  });
}

export function useFlowAction() {
  const queryClient = useQueryClient();
  const flowId = currentFlowId();

  return useMutation({
    mutationFn: async ({ action, body }: { action: string; body: Record<string, unknown> }) => {
      const flow = queryClient.getQueryData<Flow>(["flow", flowId]);
      const response = await fetch(`/oauth/flow/${flowId}/${action}`, {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json", "X-CSRF-Token": flow?.csrf ?? "" },
        body: JSON.stringify(body),
      });
      const data = await parse(response);
      if (!response.ok)
        throw friendly(
          data.error as string,
          (data.message as string) ??
            "Authorization could not be completed. Restart from your application.",
        );
      if (typeof data["client-id"] === "string")
        queryClient.setQueryData(["flow", flowId], data as unknown as Flow);
      return data as Record<string, unknown>;
    },
  });
}
