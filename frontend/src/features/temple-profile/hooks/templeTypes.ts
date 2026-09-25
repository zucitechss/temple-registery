import { DocumentResponse } from '@/features/document/documentApi'
import { z } from 'zod'

// ── Enums ─────────────────────────────────────────────────────────────────────

export const TEMPLE_GRADES = ['A', 'B', 'C'] as const
export type TempleGrade = (typeof TEMPLE_GRADES)[number]

export const RELIGIOUS_TRADITIONS = [
  'SHAIVITE', 'VAISHNAVITE', 'SHAKTA', 'JAIN', 'BUDDHIST', 'OTHER',
] as const
export type ReligiousTradition = (typeof RELIGIOUS_TRADITIONS)[number]

// ── Request schemas ───────────────────────────────────────────────────────────

export const createTempleSchema = z.object({
  name: z.string().min(1, 'Temple name is required').max(255),
  registrationNumber: z.string().min(1, 'Registration number is required').max(50),
  grade: z.enum(TEMPLE_GRADES),
  primaryDeity: z.string().min(1, 'Primary deity is required').max(150),
  tradition: z.enum(RELIGIOUS_TRADITIONS),
  districtId: z.number({ required_error: 'District is required' }),
  talukId: z.number().optional(),
  hobliId: z.number().optional(),
  addressLine1: z.string().max(255).optional(),
  addressLine2: z.string().max(255).optional(),
  city: z.string().max(128).optional(),
  pinCode: z.string().length(6, 'PIN code must be 6 digits').regex(/^\d{6}$/, 'PIN code must be 6 digits').optional(),
  latitude: z.number().min(-90).max(90).optional(),
  longitude: z.number().min(-180).max(180).optional(),
  contactName: z.string().min(1, 'Contact person name is required').max(200),
  contactDesignation: z.string().min(1, 'Contact person designation is required').max(150),
  contactMobile: z.string().regex(/^[6-9]\d{9}$/, 'Phone must be exactly 10 digits with no country code prefix'),
  contactEmail: z.string().email('Invalid email address').max(255),
  languagesOfWorship: z.string().max(255).optional(),
  trustRegistered: z.boolean().default(false),
})

export const templeSearchFilterSchema = z.object({
  name: z.string().optional(),
  grade: z.enum(TEMPLE_GRADES).optional(),
  tradition: z.enum(RELIGIOUS_TRADITIONS).optional(),
  districtId: z.number().optional(),
  talukId: z.number().optional(),
  hobliId: z.number().optional(),
  trustRegistered: z.boolean().optional(),
  assetDeclarationStatus: z.string().optional(),
  declarationStatus: z.string().optional(),
  verificationRequired: z.boolean().optional(),
  pendingProfileReview: z.boolean().optional(),
})

export type CreateTempleRequest = z.infer<typeof createTempleSchema>
export type TempleSearchFilterRequest = z.infer<typeof templeSearchFilterSchema>

// ── Response types ────────────────────────────────────────────────────────────


export interface TempleResponse {
  // ── Identity (Master — set by Super Admin, always read-only for TA) ──
  id: number
  registrationNumber?: string
  name: string
  aliasName?: string
  grade: TempleGrade
  primaryDeity?: string
  tradition: ReligiousTradition
  yearEstablished?: number
  history?: string          // historical significance / description

  // ── Geo hierarchy (IDs only; resolve names via geoApi) ──
  districtId: number
  districtName?: string
  cityId?: number
  cityName?: string
  talukId?: number
  hobliId?: number

  // ── Address ──
  doorNumber?: string       // maps to backend `doorNumber`
  street?: string           // maps to backend `street`
  villageTown?: string      // maps to backend `villageTown`
  /** Some screens still refer to `city`; treat as alias for `villageTown`. */
  city?: string
  pinCode?: string
  landmark?: string
  latitude?: number
  longitude?: number
  placeId?: string
  formattedAddress?: string

  // ── Contact (from registration; may be overridden by profile staging) ──
  contactName?: string
  contactDesignation?: string   // maps to backend `contactDesignation`
  contactMobile?: string        // maps to backend `contactMobile`
  contactEmail?: string
  photoUrl?: string
  website?: string
  languagesOfWorship?: string[]
  linkedInstitutions?: string | string[]
  annualFestivals?: string
  historicalSignificance?: string
  bankName?: string
  bankIfsc?: string

  trustRegistered: boolean
  assetDeclarationStatus?: string
  verificationStatus?: 'UNVERIFIED' | 'UNDER_REVIEW' | 'VERIFIED' | 'FLAGGED' | 'ACTIVE'
  dcRejectionReason?: string | null

  /** All media (photos, PDFs, etc.) linked to this temple. */
  media?: DocumentResponse[]
}

export interface TemplePhotoDto {
  id: number
  url: string
  primary?: boolean
  isPrimary?: boolean
  fileName?: string
  uploadDate?: string
  width?: number
  height?: number
}

export interface TempleSearchResultResponse {
  id: number
  templeId?: number
  registrationNumber?: string
  name: string
  grade: TempleGrade
  primaryDeity?: string
  tradition: ReligiousTradition
  districtId?: number
  districtName?: string
  city?: string
  trustRegistered: boolean
  assetDeclarationStatus?: string
  declarationStatus?: string
  photoUrl?: string
  latitude?: number
  longitude?: number
}

// ── Temple Profile Staging ────────────────────────────────────────────────────

export type TempleProfileStagingStatus = 'DRAFT' | 'SUBMITTED' | 'UNDER_REVIEW' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED'

export const createTempleProfileStagingSchema = z.object({
  phone: z.string().regex(/^[0-9]{10}$/, 'Phone must be exactly 10 digits').optional().or(z.literal('')),
  email: z.string().email('Invalid email address').optional().or(z.literal('')),
  website: z.string().max(500).optional(),
  contactPersonName: z.string().max(255).optional(),
  contactPersonDesignation: z.string().max(100).optional(),
  photoFilePath: z.string().max(1000).optional(),
  languagesOfWorship: z.string().max(500).optional(),
  linkedInstitutions: z.string().optional(),
  description: z.string().optional(),
  annualFestivals: z.string().optional(),
  landmark: z.string().optional(),
  historicalSignificance: z.string().optional(),
})

export type CreateTempleProfileStagingRequest = z.infer<typeof createTempleProfileStagingSchema>

export interface TempleProfileStagingResponse {
  id: number
  templeId: number
  versionNumber: number
  statusLabel: TempleProfileStagingStatus
  /** Workflow instance ID — used by WorkflowGovernancePanel */
  workflowInstanceId?: number
  phone?: string
  email?: string
  website?: string
  contactPersonName?: string
  contactPersonDesignation?: string
  photoUrl?: string
  bankAccountMasked?: string
  bankName?: string
  bankIfsc?: string
  languagesOfWorship?: string | string[]
  linkedInstitutions?: string | string[]
  description?: string
  annualFestivals?: string
  landmark?: string
  historicalSignificance?: string
  // Identity fields (V93)
  aliasName?: string
  primaryDeity?: string
  grade?: string
  tradition?: string
  hobliId?: number
  talukId?: number
  addressLine1?: string
  pinCode?: string
  latitude?: number
  longitude?: number
  placeId?: string
  formattedAddress?: string
  yearEstablished?: number
  reviewComment?: string
  submittedAt?: string
  reviewedAt?: string
  createdAt: string
  updatedAt: string
}

// ─── TA Self-Service Profile (endpoints at /api/v1/ta/profile) ────────────────

export type TaProfileStatus = 'NOT_STARTED' | 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'FLAGGED' | 'UPDATED_AFTER_APPROVAL' | 'RESUBMITTED'

/** 
 * Backend API request for profile staging.
 * Mapped to com.templeregistry.dto.request.ta.TaProfileStagingRequest.
 */
export interface TaProfileStagingRequest {
  phone?: string
  email?: string
  website?: string
  contactPersonName?: string
  contactPersonDesignation?: string
  photoFilePath?: string
  bankAccountNumber?: string
  bankName?: string
  bankIfsc?: string
  /** Backend expects plain string (comma-separated or JSON-string), not arrays. */
  languagesOfWorship?: string
  linkedInstitutions?: string
  description?: string
  annualFestivals?: string
  landmark?: string
  historicalSignificance?: string
  // Identity fields (V93)
  aliasName?: string
  primaryDeity?: string
  grade?: string
  tradition?: string
  hobliId?: number
  talukId?: number
  addressLine1?: string
  pinCode?: string
  latitude?: number | null
  longitude?: number | null
  placeId?: string | null
  formattedAddress?: string | null
  yearEstablished?: number | null
}

/** 
 * Frontend Zod schema for the edit form.
 * Uses strings for comma-separated inputs; joined/split in the hook before API call.
 */
export const taProfileStagingSchema = z.object({
  phone: z
    .string()
    .regex(/^[0-9]{10}$/, 'Phone must be exactly 10 digits')
    .optional()
    .or(z.literal('')),
  email: z.string().email('Invalid email address').optional().or(z.literal('')),
  website: z.string().url('Must be a valid URL (e.g. https://…)').optional().or(z.literal('')),
  contactPersonName: z.string().max(255).optional(),
  contactPersonDesignation: z.string().max(100).optional(),
  photoFilePath: z.string().max(1000).optional(),
  languagesOfWorship: z.string().max(500).optional(),
  linkedInstitutions: z.string().optional(),
  description: z.string().optional(),
  annualFestivals: z.string().optional(),
  landmark: z.string().optional(),
  historicalSignificance: z.string().optional(),
  bankAccountNumber: z.string().optional(),
  bankName: z.string().max(100).optional(),
  bankIfsc: z
    .string()
    .regex(/^(?=.{11}$)[A-Z]{4}0[A-Z0-9]{6}$/, 'Must be a valid 11-char IFSC code (e.g. SBIN0001234)')
    .optional()
    .or(z.literal('')),
  // Identity fields (V93)
  aliasName: z.string().max(255).optional(),
  primaryDeity: z.string().max(150).optional(),
  grade: z.enum(['A', 'B', 'C']).optional(),
  tradition: z.enum(['SHAIVITE', 'VAISHNAVITE', 'SHAKTA', 'JAIN', 'BUDDHIST', 'OTHER']).optional(),
  hobliId: z.number().int().positive().optional(),
  talukId: z.number().int().positive().optional(),
  addressLine1: z.string().max(255).optional(),
  pinCode: z.string().regex(/^\d{6}$/, 'PIN code must be 6 digits').optional().or(z.literal('')),
  latitude: z.number().min(-90).max(90).optional().nullable(),
  longitude: z.number().min(-180).max(180).optional().nullable(),
  placeId: z.string().max(500).optional().nullable(),
  formattedAddress: z.string().max(1000).optional().nullable(),
  yearEstablished: z.number().int().min(500).max(new Date().getFullYear()).optional().nullable(),
})

/** Stricter schema used at submit time — contactPersonName + contactPersonDesignation become required. */
export const submitTempleProfileSchema = taProfileStagingSchema.extend({
  contactPersonName: z.string().min(1, 'Contact person name is required').max(255),
  contactPersonDesignation: z.string().min(1, 'Contact person designation is required').max(100),
  phone: z.string().regex(/^[0-9]{10}$/, 'Phone must be exactly 10 digits'),
  email: z.string().email('Invalid email address'),
})

/** Type for the form state. */
export type TaProfileStagingFormValues = z.infer<typeof taProfileStagingSchema>
export type TaProfileStagingSubmitRequest = z.infer<typeof submitTempleProfileSchema>

export interface TaCurrentProfileResponse {
  id: number
  templeId: number
  phone?: string
  email?: string
  website?: string
  contactPersonName?: string
  contactPersonDesignation?: string
  /** Backend serializes as "photoFilePath" — use this field to get the approved photo path. */
  photoFilePath?: string
  /** Alias kept for backward compatibility. Backend sends "photoFilePath", not "photoUrl". */
  photoUrl?: string
  bankAccountMasked?: string
  bankName?: string
  bankIfsc?: string
  languagesOfWorship?: string | string[]
  linkedInstitutions?: string | string[]
  description?: string
  annualFestivals?: string
  landmark?: string
  historicalSignificance?: string
  publishedAt: string
  updatedAt: string
}

export interface TaFullProfileResponse {
  temple: TempleResponse
  /** Null if the profile has never been approved */
  currentProfile: TaCurrentProfileResponse | null
  /** Active DRAFT or SUBMITTED staging record; null if none */
  stagingProfile: TempleProfileStagingResponse | null
  profileStatus: TaProfileStatus
}

export interface TaProfileHistoryItemResponse {
  id: number
  version: number
  /** HISTORY = once-live approved version; REJECTED_STAGING = DC-rejected, never promoted */
  source: 'HISTORY' | 'REJECTED_STAGING'
  status: 'APPROVED' | 'REJECTED'
  contactPersonName?: string
  contactPersonDesignation?: string
  phone?: string
  email?: string
  website?: string
  photoUrl?: string
  bankName?: string
  bankIfsc?: string
  languagesOfWorship?: string | string[]
  linkedInstitutions?: string | string[]
  description?: string
  annualFestivals?: string
  publishedAt?: string
  reviewComment?: string
  createdAt: string
}
