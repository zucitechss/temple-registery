import { z } from 'zod'
import type { UserRole } from '@/constants/roles'

// ── Zod schemas ──────────────────────────────────────────────────────────────

export const loginSchema = z.object({
  username: z.string().min(3, 'Username is required'),
  password: z.string().min(8, 'Password must be at least 8 characters'),
})

export const mfaVerifySchema = z.object({
  tempToken: z.string().min(1),
  mfaCode: z.string().length(6, 'OTP must be 6 digits').optional().nullable(),
})

export const aadhaarOtpRequestSchema = z.object({
  aadhaarNumber: z
    .string()
    .length(12, 'Aadhaar number must be 12 digits')
    .regex(/^\d{12}$/, 'Aadhaar must contain only digits'),
})

export const aadhaarOtpVerifySchema = z.object({
  aadhaarNumber: z.string().length(12),
  otp: z.string().length(6, 'OTP must be 6 digits'),
})

export const registerSchema = z
  .object({
    username: z.string().min(3).max(64),
    email: z.string().email('Invalid email address'),
    password: z.string().min(8).max(128),
    confirmPassword: z.string(),
    fullName: z.string().min(1).max(128),
    mobile: z.string().regex(/^[6-9]\d{9}$/, 'Invalid Indian mobile number'),
    aadhaarVerificationToken: z.string().min(1, 'Aadhaar verification required'),
  })
  .refine((d) => d.password === d.confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  })

export const passwordResetRequestSchema = z.object({
  email: z.string().email('Invalid email address'),
})

export const passwordResetConfirmSchema = z
  .object({
    token: z.string().min(1),
    newPassword: z.string().min(8).max(128),
    confirmPassword: z.string(),
  })
  .refine((d) => d.newPassword === d.confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  })

/**
 * Minimum length mirrors the backend policy (`@Size(min = 8, max = 128)` on
 * ChangePasswordRequest / PasswordResetConfirmRequest). The backend re-validates everything
 * here — this only gives the user immediate feedback.
 */
export const changePasswordSchema = z
  .object({
    currentPassword: z.string().min(1, 'Current password is required'),
    newPassword: z
      .string()
      .min(8, 'Password must be at least 8 characters')
      .max(128, 'Password must be at most 128 characters'),
    confirmPassword: z.string().min(1, 'Please confirm your new password'),
  })
  .refine((d) => d.newPassword === d.confirmPassword, {
    message: 'New password and confirmation password do not match',
    path: ['confirmPassword'],
  })
  .refine((d) => d.newPassword !== d.currentPassword, {
    message: 'New password must be different from the current password',
    path: ['newPassword'],
  })

// ── Inferred TypeScript types ─────────────────────────────────────────────────

export type LoginRequest = z.infer<typeof loginSchema>
export type MfaVerifyRequest = z.infer<typeof mfaVerifySchema>
export type AadhaarOtpRequest = z.infer<typeof aadhaarOtpRequestSchema>
export type AadhaarOtpVerifyRequest = z.infer<typeof aadhaarOtpVerifySchema>
export type RegisterRequest = z.infer<typeof registerSchema>
export type PasswordResetRequest = z.infer<typeof passwordResetRequestSchema>
export type PasswordResetConfirmRequest = z.infer<typeof passwordResetConfirmSchema>
export type ChangePasswordRequest = z.infer<typeof changePasswordSchema>

// ── Response types ────────────────────────────────────────────────────────────

export interface AuthTokenResponse {
  accessToken: string
  expiresIn: number
  role: string
  userId: number
}

export interface MfaChallengeResponse {
  mfaRequired: boolean
  tempToken: string
  challengeType: 'TOTP' | 'SMS_OTP'
  maskedMobile?: string
}

export interface AadhaarOtpResponse {
  verificationToken: string
  maskedAadhaar: string
}

export interface CurrentUser {
  userId: number
  username: string
  email?: string
  fullName: string
  mobile?: string
  role: UserRole
  active?: boolean
  districtId?: number
  templeId?: number
  aadhaarVerified: boolean
  designation?: string
  /** VIEW = read-only; EDIT = full write access. Only meaningful for TEMPLE_AUTHORITY. */
  accessType?: 'VIEW' | 'EDIT'
  /** Resolved names for the assigned districtId / templeId, when assigned. */
  districtName?: string
  templeName?: string
  /** ISO timestamps. Absent when the user has never logged in / never changed their password. */
  lastLoginAt?: string
  passwordUpdatedAt?: string
  /** True while an admin-issued temporary password must still be replaced. */
  mustChangePassword?: boolean
  completionChecklist?: TempleCompletionChecklist
}

export interface TempleCompletionChecklist {
  templeProfileStatus: string | null
  trustExists: boolean
  employeeCount: number
  contractorCount: number
  latestDeclarationStatus: string | null
}
