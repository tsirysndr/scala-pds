import { z } from "zod";

const password = z
  .string()
  .min(8, "Passwords need at least 8 characters")
  .max(1024, "That password is too long");

export const loginSchema = z.object({
  identifier: z
    .string()
    .trim()
    .min(1, "Enter your username, email address, or DID")
    .max(2048, "That identifier is too long"),
  password: z.string().min(1, "Enter your password").max(1024, "That password is too long"),
});

export type LoginValues = z.infer<typeof loginSchema>;

export const factorSchema = z.object({
  code: z.string().trim().min(6, "Enter the code you received").max(64, "That code is too long"),
});

export type FactorValues = z.infer<typeof factorSchema>;

/** Whether an invite code is demanded follows the server, so the schema does. */
export const signupSchema = (inviteRequired: boolean) =>
  z.object({
    username: z
      .string()
      .trim()
      .min(3, "Usernames need at least three characters")
      .max(63, "That username is too long")
      .regex(
        /^[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?$/,
        "Use letters, numbers, and inner hyphens",
      ),
    email: z
      .string()
      .email("Enter a valid email address")
      .max(320, "That email address is too long"),
    password,
    // When no code is required the field is not shown and stays empty, so it
    // has to validate as empty.
    inviteCode: inviteRequired
      ? z.string().trim().min(1, "Enter your invite code").max(256, "That invite code is too long")
      : z.string().trim().max(256, "That invite code is too long"),
  });

export type SignupValues = z.infer<ReturnType<typeof signupSchema>>;

export const totpCodeSchema = z.object({
  code: z
    .string()
    .trim()
    .regex(/^(?:[0-9]{6}|[A-Z2-7]{26})$/, "Enter a 6-digit code or a recovery code"),
});

export type TotpCodeValues = z.infer<typeof totpCodeSchema>;

export const appPasswordSchema = z.object({
  name: z.string().trim().min(1, "Name this app password").max(64, "That name is too long"),
  privileged: z.boolean(),
});

export type AppPasswordValues = z.infer<typeof appPasswordSchema>;

export const passkeyNameSchema = z.object({
  name: z.string().trim().min(1, "Name this passkey").max(64, "That name is too long"),
});

export type PasskeyNameValues = z.infer<typeof passkeyNameSchema>;

export const passwordChangeSchema = z
  .object({
    currentPassword: z.string().min(1, "Enter your current password").max(1024),
    newPassword: password,
    confirmPassword: z.string(),
  })
  .refine((value) => value.newPassword === value.confirmPassword, {
    path: ["confirmPassword"],
    message: "Those passwords do not match",
  });

export type PasswordChangeValues = z.infer<typeof passwordChangeSchema>;
