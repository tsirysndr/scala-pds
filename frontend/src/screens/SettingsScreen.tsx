import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button, Divider, Snippet, Switch } from "@heroui/react";
import { useAtom } from "jotai";
import { IconDeviceMobile, IconKey, IconLogout, IconShieldLock } from "@tabler/icons-react";
import type { Session } from "../api";
import { useAction } from "../api";
import {
  appPasswordSchema,
  passwordChangeSchema,
  totpCodeSchema,
  type AppPasswordValues,
  type PasswordChangeValues,
  type TotpCodeValues,
} from "../schemas";
import { appPasswordAtom, recoveryCodesAtom, totpEnrollmentAtom } from "../atoms";
import { AuthCard } from "../components/AuthCard";
import { Alert } from "../components/Alert";
import { TextField, PasswordField } from "../components/Field";
import { usePending } from "../pending";

function Section({ title, icon, children }: { title: string; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-3">
      <h2 className="flex items-center gap-2 text-sm font-semibold">
        <span className="text-brand">{icon}</span>
        {title}
      </h2>
      {children}
    </section>
  );
}

export function SettingsScreen({ session }: { session: Session }) {
  const action = useAction();
  const { run, buttonProps, notice } = usePending();
  const [enrollment, setEnrollment] = useAtom(totpEnrollmentAtom);
  const [recoveryCodes, setRecoveryCodes] = useAtom(recoveryCodesAtom);
  const [created, setCreated] = useAtom(appPasswordAtom);

  const appPassword = useForm<AppPasswordValues>({
    resolver: zodResolver(appPasswordSchema),
    defaultValues: { name: "", privileged: false },
  });

  const totp = useForm<TotpCodeValues>({
    resolver: zodResolver(totpCodeSchema),
    defaultValues: { code: "" },
  });

  const passwords = useForm<PasswordChangeValues>({
    resolver: zodResolver(passwordChangeSchema),
    defaultValues: { currentPassword: "", newPassword: "", confirmPassword: "" },
  });

  const createAppPassword = appPassword.handleSubmit((values) =>
    run("app-password", async () => {
      const result = await action.mutateAsync({ action: "app-passwords/create", body: values });
      setCreated({ name: values.name, password: result.password as string });
      appPassword.reset();
    }),
  );

  const confirmTotp = totp.handleSubmit((values) =>
    run("totp-confirm", async () => {
      const result = await action.mutateAsync({ action: "totp/confirm", body: values });
      setRecoveryCodes(result.recoveryCodes as string[]);
      setEnrollment(null);
      totp.reset();
    }),
  );

  const changePassword = passwords.handleSubmit((values) =>
    run("password", async () => {
      await action.mutateAsync({
        action: "password/change",
        body: { currentPassword: values.currentPassword, newPassword: values.newPassword },
      });
      passwords.reset();
    }),
  );

  return (
    <AuthCard title="Your account" service={session.origin} subtitle={session.handle} width="full">
      {notice ? <Alert tone={notice.tone}>{notice.text}</Alert> : null}

      <Section title="Two-factor authentication" icon={<IconShieldLock size={18} stroke={1.75} />}>
        {session.factor === "totp" ? (
          <form
            onSubmit={(event) => {
              event.preventDefault();
              void run("totp-disable", async () => {
                await action.mutateAsync({
                  action: "totp/disable",
                  body: { code: totp.getValues("code") },
                });
                totp.reset();
              });
            }}
            className="flex flex-col gap-3"
          >
            <p className="text-sm text-default-500">
              An authenticator is enabled. {session.recoveryCodes ?? 0} recovery codes remain.
            </p>
            <TextField
              label="Authenticator code"
              placeholder="123456"
              registration={totp.register("code")}
              error={totp.formState.errors.code}
              inputMode="numeric"
              maxLength={64}
            />
            <Button type="submit" variant="bordered" radius="sm" {...buttonProps("totp-disable")}>
              Turn off the authenticator
            </Button>
          </form>
        ) : enrollment ? (
          <form onSubmit={(event) => void confirmTotp(event)} className="flex flex-col gap-3">
            <p className="text-sm text-default-500">
              Add this secret to your authenticator, then enter the code it shows.
            </p>
            <Snippet size="sm" radius="sm" symbol="" className="font-mono">
              {enrollment.secret}
            </Snippet>
            <TextField
              label="Authenticator code"
              placeholder="123456"
              registration={totp.register("code")}
              error={totp.formState.errors.code}
              inputMode="numeric"
              maxLength={64}
              autoFocus
            />
            <Button
              type="submit"
              color="primary"
              radius="sm"
              {...buttonProps("totp-confirm")}
              className="font-medium"
            >
              Confirm
            </Button>
          </form>
        ) : (
          <Button
            variant="bordered"
            radius="sm"
            {...buttonProps("totp-begin")}
            onPress={() =>
              void run("totp-begin", async () => {
                const result = await action.mutateAsync({ action: "totp/begin" });
                setEnrollment({ secret: result.secret as string, uri: result.uri as string });
              })
            }
          >
            Set up an authenticator
          </Button>
        )}

        {recoveryCodes ? (
          <div className="flex flex-col gap-2 rounded-xl border border-default-200 p-3">
            <p className="text-sm font-medium">Save these recovery codes now</p>
            <ul className="grid grid-cols-2 gap-1 font-mono text-xs">
              {recoveryCodes.map((code) => (
                <li key={code}>{code}</li>
              ))}
            </ul>
            <Button size="sm" variant="light" radius="sm" onPress={() => setRecoveryCodes(null)}>
              I saved them
            </Button>
          </div>
        ) : null}

        {session["email-enabled"] ? (
          <Switch
            size="sm"
            isSelected={session.factor === "email"}
            isDisabled={session.factor === "totp"}
            onValueChange={(enabled) =>
              void run("email-factor", async () => {
                await action.mutateAsync({ action: enabled ? "email/enable" : "email/disable" });
              })
            }
          >
            Email a one-time code when signing in
          </Switch>
        ) : null}
      </Section>

      <Divider />

      <Section title="App passwords" icon={<IconDeviceMobile size={18} stroke={1.75} />}>
        <ul className="flex flex-col gap-2">
          {(session.appPasswords ?? []).map((entry) => (
            <li
              key={entry.name}
              className="flex items-center justify-between gap-3 rounded-xl border border-default-200 px-3 py-2"
            >
              <div className="min-w-0">
                <p className="truncate text-sm font-medium">{entry.name}</p>
                <p className="text-xs text-default-500">
                  {entry.privileged ? "Privileged" : "Standard"} · {entry.createdAt.slice(0, 10)}
                </p>
              </div>
              <Button
                size="sm"
                variant="light"
                color="danger"
                radius="sm"
                {...buttonProps(`revoke-${entry.name}`)}
                onPress={() =>
                  void run(`revoke-${entry.name}`, async () => {
                    await action.mutateAsync({
                      action: "app-passwords/revoke",
                      body: { name: entry.name },
                    });
                  })
                }
              >
                Revoke
              </Button>
            </li>
          ))}
          {(session.appPasswords ?? []).length === 0 ? (
            <li className="text-sm text-default-500">No app passwords yet.</li>
          ) : null}
        </ul>

        {created ? (
          <div className="flex flex-col gap-2 rounded-xl border border-default-200 p-3">
            <p className="text-sm font-medium">Copy “{created.name}” now — it is shown once</p>
            <Snippet size="sm" radius="sm" symbol="" className="font-mono">
              {created.password}
            </Snippet>
            <Button size="sm" variant="light" radius="sm" onPress={() => setCreated(null)}>
              Done
            </Button>
          </div>
        ) : null}

        <form onSubmit={(event) => void createAppPassword(event)} className="flex flex-col gap-3">
          <TextField
            label="Name"
            placeholder="Phone"
            registration={appPassword.register("name")}
            error={appPassword.formState.errors.name}
            maxLength={64}
          />
          <Switch size="sm" {...appPassword.register("privileged")}>
            Allow direct messages and account settings
          </Switch>
          <Button
            type="submit"
            color="primary"
            radius="sm"
            {...buttonProps("app-password")}
            className="font-medium"
          >
            Create an app password
          </Button>
        </form>
      </Section>

      <Divider />

      <Section title="Connected applications" icon={<IconKey size={18} stroke={1.75} />}>
        <ul className="flex flex-col gap-2">
          {(session.oauthSessions ?? []).map((entry) => (
            <li key={entry.id} className="flex flex-col gap-2 rounded-xl border border-default-200 p-3">
              <p className="text-sm font-medium wrap-anywhere">{entry.clientId}</p>
              <ul className="flex flex-col gap-1 text-xs text-default-500">
                {entry.permissions.map((permission) => (
                  <li key={permission}>{permission}</li>
                ))}
              </ul>
              <Button
                size="sm"
                variant="light"
                color="danger"
                radius="sm"
                className="self-start"
                {...buttonProps(`oauth-${entry.id}`)}
                onPress={() =>
                  void run(`oauth-${entry.id}`, async () => {
                    await action.mutateAsync({ action: "oauth/revoke", body: { id: entry.id } });
                  })
                }
              >
                Revoke access
              </Button>
            </li>
          ))}
          {(session.oauthSessions ?? []).length === 0 ? (
            <li className="text-sm text-default-500">No applications are connected.</li>
          ) : null}
        </ul>
      </Section>

      <Divider />

      <Section title="Password" icon={<IconLogout size={18} stroke={1.75} />}>
        <form onSubmit={(event) => void changePassword(event)} className="flex flex-col gap-3">
          <PasswordField
            label="Current password"
            registration={passwords.register("currentPassword")}
            error={passwords.formState.errors.currentPassword}
            autoComplete="current-password"
          />
          <PasswordField
            label="New password"
            registration={passwords.register("newPassword")}
            error={passwords.formState.errors.newPassword}
            autoComplete="new-password"
          />
          <PasswordField
            label="Confirm new password"
            registration={passwords.register("confirmPassword")}
            error={passwords.formState.errors.confirmPassword}
            autoComplete="new-password"
          />
          <Button
            type="submit"
            color="primary"
            radius="sm"
            {...buttonProps("password")}
            className="font-medium"
          >
            Change password
          </Button>
        </form>

        <Button
          variant="bordered"
          radius="sm"
          {...buttonProps("logout")}
          onPress={() => void run("logout", () => action.mutateAsync({ action: "logout" }).then(() => undefined))}
        >
          Sign out
        </Button>
      </Section>
    </AuthCard>
  );
}
