import { z } from "zod";

type T = (key: string) => string;

const password = (t: T) =>
  z
    .string()
  .min(8, t("schema.passwordMin"))
  .max(1024, t("schema.passwordLong"));

export const loginSchema = (t: T) =>
  z.object({
  identifier: z
    .string()
    .trim()
    .min(1, t("schema.identifierRequired"))
    .max(2048, t("schema.identifierLong")),
  password: z.string().min(1, t("schema.passwordRequired")).max(1024, t("schema.passwordLong")),
});

export type LoginValues = z.infer<ReturnType<typeof loginSchema>>;

export const factorSchema = (t: T) =>
  z.object({
  code: z.string().trim().min(6, t("schema.codeRequired")).max(64, t("schema.codeLong")),
});

export type FactorValues = z.infer<ReturnType<typeof factorSchema>>;

/** Whether an invite code is demanded follows the server, so the schema does. */
export const signupSchema = (t: T, inviteRequired: boolean) =>
  z
    .object({
      username: z
        .string()
        .trim()
        .min(3, t("schema.usernameMin"))
        .max(63, t("schema.usernameLong"))
        .regex(
          /^[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?$/,
          t("schema.usernameShape"),
        ),
      email: z
        .string()
        .email(t("schema.emailValid"))
        .max(320, t("schema.emailLong")),
      password: password(t),
      confirmPassword: z.string(),
      // When no code is required the field is not shown and stays empty, so it
      // has to validate as empty.
      inviteCode: inviteRequired
        ? z.string().trim().min(1, t("schema.inviteRequired")).max(256, t("schema.inviteLong"))
        : z.string().trim().max(256, t("schema.inviteLong")),
    })
    .refine((value) => value.password === value.confirmPassword, {
      path: ["confirmPassword"],
      message: t("schema.noMatch"),
    });

export type SignupValues = z.infer<ReturnType<typeof signupSchema>>;

export const totpCodeSchema = (t: T) =>
  z.object({
  code: z
    .string()
    .trim()
    .regex(/^(?:[0-9]{6}|[A-Z2-7]{26})$/, t("schema.totpShape")),
});

export type TotpCodeValues = z.infer<ReturnType<typeof totpCodeSchema>>;

export const appPasswordSchema = (t: T) =>
  z.object({
  name: z.string().trim().min(1, t("schema.appPasswordName")).max(64, t("schema.passkeyNameLong")),
  privileged: z.boolean(),
});

export type AppPasswordValues = z.infer<ReturnType<typeof appPasswordSchema>>;

export const passkeyNameSchema = (t: T) =>
  z.object({
  name: z.string().trim().min(1, t("schema.passkeyName")).max(64, t("schema.passkeyNameLong")),
});

export type PasskeyNameValues = z.infer<ReturnType<typeof passkeyNameSchema>>;

export const passwordChangeSchema = (t: T) =>
  z
  .object({
    currentPassword: z.string().min(1, t("schema.currentPassword")).max(1024),
    newPassword: password(t),
    confirmPassword: z.string(),
  })
  .refine((value) => value.newPassword === value.confirmPassword, {
    path: ["confirmPassword"],
    message: t("schema.noMatch"),
  });

export type PasswordChangeValues = z.infer<ReturnType<typeof passwordChangeSchema>>;
