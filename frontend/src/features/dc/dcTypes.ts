import { z } from 'zod'
import { DC_DECLARATION_FILTER_STATUSES, type DcDeclarationFilterStatus } from './declarationStatusFilters'
import type { DeclarationStatus as AssetDeclarationStatus } from '@/features/declaration/declarationTypes'

// ─── Enums ───────────────────────────────────────────────────────────────────

export const DC_ROLES = ['DISTRICT_COLLECTOR', 'DC_STAFF', 'SUPER_ADMIN'] as const
export type DcRole = (typeof DC_ROLES)[number]

export const DECLARATION_STATUSES = DC_DECLARATION_FILTER_STATUSES
export type DeclarationStatus = DcDeclarationFilterStatus

export const EXPORT_FORMATS = ['CSV', 'PDF'] as const
export type ExportFormat = (typeof EXPORT_FORMATS)[number]

// ─── Request schemas ─────────────────────────────────────────────────────────

export const dcTempleSearchFilterSchema = z.object({
  districtId: z.number().optional(),
  cityId: z.number().optional(),
  talukId: z.number().optional(),
  hobliId: z.number().optional(),
  grade: z.array(z.string()).optional(),
  keyword: z.string().optional(),
  deityName: z.string().optional(),
  tradition: z.string().optional(),
  trustRegistered: z.boolean().optional(),
  declarationStatus: z.enum(DC_DECLARATION_FILTER_STATUSES).optional(),
  hasApprovedDeclaration: z.boolean().optional(),
  pendingProfileReview: z.boolean().optional(),
  establishedYearFrom: z.number().optional(),
  establishedYearTo: z.number().optional(),
  page: z.number().default(0),
  size: z.number().default(10),
  sort: z.string().optional(),
})

export const workflowApproveSchema = z.object({
  notes: z.string().max(1000).optional(),
})

export const workflowRejectSchema = z.object({
  reason: z
    .string()
    .min(50, 'Rejection reason must be at least 50 characters.')
    .max(1000, 'Reason cannot exceed 1000 characters.')
    .refine((v) => v.trim().length >= 50, {
      message: 'Rejection reason must contain at least 50 non-whitespace characters.',
    }),
})

export const dcClarifySchema = z.object({
  notes: z
    .string()
    .min(20, 'Clarification notes must be at least 20 characters.')
    .max(2000, 'Notes cannot exceed 2000 characters.')
    .refine((v) => v.trim().length >= 20, {
      message: 'Clarification notes must contain at least 20 non-whitespace characters.',
    }),
})

export const approveProfileSchema = z.object({
  remarks: z.string().max(1000).optional(),
})

export const rejectProfileSchema = z.object({
  reason: z
    .string()
    .min(10, 'Rejection reason must be at least 10 characters.')
    .max(2000, 'Rejection reason cannot exceed 2000 characters.'),
})

export const dcFlagSchema = z.object({
  reason: z.string().min(1, 'Flag reason is required').max(1000),
})

export const dcVerifySchema = z.object({
  notes: z.string().max(1000).optional(),
})


export const exportTemplesSchema = z.object({
  districtId: z.number().optional(),
  grade: z.string().optional(),
  tradition: z.string().optional(),
  trustRegistered: z.boolean().optional(),
  format: z.enum(EXPORT_FORMATS),
})

export const exportDeclarationsSchema = z.object({
  districtId: z.number().optional(),
  financialYear: z.string().optional(),
  status: z.string().optional(),
  format: z.enum(EXPORT_FORMATS),
})

export type DcTempleSearchFilterRequest = z.infer<typeof dcTempleSearchFilterSchema>
export type WorkflowApproveRequest = z.infer<typeof workflowApproveSchema>
export type WorkflowRejectRequest = z.infer<typeof workflowRejectSchema>
export type DcClarifyRequest = z.infer<typeof dcClarifySchema>
export type ApproveProfileRequest = z.infer<typeof approveProfileSchema>
export type RejectProfileRequest = z.infer<typeof rejectProfileSchema>
export type ExportTemplesRequest = z.infer<typeof exportTemplesSchema>
export type ExportDeclarationsRequest = z.infer<typeof exportDeclarationsSchema>
export type DcFlagRequest = z.infer<typeof dcFlagSchema>
export type DcVerifyRequest = z.infer<typeof dcVerifySchema>

type DcDeclarationApiStatus =
  | AssetDeclarationStatus
  | 'PENDING_REVIEW'
  | 'CLARIFICATION_REQUESTED'
  | 'PHYSICAL_VERIFICATION_REQUESTED'

// ─── Response types ───────────────────────────────────────────────────────────

export interface GradeDistributionItem {
  grade: string
  count: number
}

export interface AreaDistributionItem {
  areaId: number
  count: number
}

export interface DcContextResponse {
  userId: number
  username: string
  fullName: string
  role: DcRole
  aadhaarVerified: boolean
  districtId: number | null
  districtName: string | null
  cityId: number | null
}

export interface DcDashboardResponse {
  totalTemples: number
  pendingDeclarations: number
  overdueDeclarations: number
  pendingProfileReviews: number
  templesWithoutApprovedDeclaration: number
  gradeDistribution: GradeDistributionItem[]
  talukDistribution?: AreaDistributionItem[]
  districtDistribution?: AreaDistributionItem[]
}

export interface DcTempleSearchItemResponse {
  templeId: number
  registrationNumber: string | null
  name: string
  grade: string
  primaryDeity: string | null
  tradition: string
  hobliId: number | null
  talukId: number | null
  districtId: number | null
  cityId: number | null
  templeStatus: string
  trustRegistered: boolean
  assetDeclarationStatus: string | null
  yearEstablished: number | null
  photoUrl: string | null
  pendingDeclarations: number
  overdueDeclarations: number
  pendingProfileReview: number
  hasActiveTrust: boolean
  hasApprovedDeclaration: boolean
  lastDeclarationAt: string | null
  lastProfileUpdateAt: string | null
}

export interface WorkflowActionResponse {
  declarationId: number
  newStatus: string
  acknowledgementNumber: string | null
  message: string
}

export interface ClarificationItemResponse {
  id: number
  direction: string
  message?: string
  notes: string
  sectionName?: string | null
  fieldNamesJson?: string | null
  respondedAt: string | null
  createdAt: string
}

export interface DeclImmovAgriLandResponse {
  id: number
  surveyNumber: string | null
  village: string | null
  areaAcres: number | null
  ownerOfRecord: string | null
  pattaStatus: string | null
}

export interface DeclImmovBuildingResponse {
  id: number
  location: string | null
  totalAreaSqft: number | null
  yearBuilt: number | null
  structureType: string | null
  valuationInr: number | null
}

export interface DeclImmovLeasedResponse {
  id: number
  propertyAddress: string | null
  lesseeName: string | null
  leaseStartDate: string | null
  leaseEndDate: string | null
  monthlyRent: number | null
  agreementDocumentId: number | null
}

export interface DeclImmovOtherResponse {
  id: number
  location: string | null
  area: number | null
  usageType: string | null
  revenueDepartmentReference: string | null
}

export interface DeclMovArtifactResponse {
  id: number
  itemDescription: string | null
  material: string | null
  ageOrPeriod: string | null
  provenance: string | null
  museumGradeClassification: string | null
  approximateValueInr: number | null
  estimatedValueInr?: number | null
}

export interface DeclMovEquipmentResponse {
  id: number
  itemName: string | null
  serialNumber: string | null
  estimatedValueInr: number | null
}

export interface DeclMovPreciousMetalResponse {
  id: number
  itemDescription: string | null
  metalType: string | null
  weightGrams: number | null
  purity: string | null
  approximateValueInr: number | null
  estimatedValueInr?: number | null
}

export interface DeclMovVehicleResponse {
  id: number
  registrationNumber: string | null
  makeModel: string | null
  year: number | null
  purpose: string | null
  estimatedValueInr?: number | null
}

export interface DeclarationDetailResponse {
  id: number
  templeId: number
  districtId: number
  financialYear: string
  versionNumber: number
  status: DcDeclarationApiStatus
  agriculturalLandAcres: number | null
  agriculturalLandValue: number | null
  buildingsSqft: number | null
  buildingsValue: number | null
  leasedPropertiesCount: number | null
  leasedPropertiesValue: number | null
  otherLandValue: number | null
  goldGrams: number | null
  silverGrams: number | null
  idolsCount: number | null
  vehiclesCount: number | null
  financialAssetsValue: number | null
  otherMovableValue: number | null
  submittedAt: string | null
  reviewedAt: string | null
  reviewedBy?: number | null
  remarks?: string | null
  acknowledgementNumber: string | null
  dueDate: string | null
  clarificationRound: number
  overdue: boolean
  clarifications: ClarificationItemResponse[]
  agriculturalLands: DeclImmovAgriLandResponse[]
  agricultureLands?: DeclImmovAgriLandResponse[]
  buildings: DeclImmovBuildingResponse[]
  leasedProperties: DeclImmovLeasedResponse[]
  otherLands: DeclImmovOtherResponse[]
  otherImmovables?: DeclImmovOtherResponse[]
  artifacts: DeclMovArtifactResponse[]
  equipment: DeclMovEquipmentResponse[]
  preciousMetals: DeclMovPreciousMetalResponse[]
  vehicles: DeclMovVehicleResponse[]
}

export interface DcTrustSummary {
  id: number
  trustName: string
  trustType: string | null
  registrationNumber: string | null
  registeringAuthority: string | null
  dateOfRegistration: string | null
  panNumberMasked: string | null
  bankAccountMasked: string | null
  bankName: string | null
  bankBranch: string | null
  annualIncome: number | null
  isVerifiedByDc: boolean
  dcFlagReason: string | null
  reviewStatus: 'APPROVED' | 'PENDING' | 'FLAGGED'
  /** Raw workflow status (SUBMITTED, APPROVED, SENT_BACK, DRAFT, REJECTED…). Used to gate DC actions. */
  workflowStatus?: string | null
  /** Canonical governance status — single source of truth. Replaces workflowStatus, reviewStatus, isVerifiedByDc. */
  governanceStatus?: import('@/types/workflow').GovernanceStatusPayload
  validationIssues: string[]
  financialStatus: 'SUBMITTED' | 'MISSING'
}

export interface TrustFinancialSummary {
  financialYear: string
  annualIncome: number | null
  annualExpenditure: number | null
}

export interface ProfileCurrentResponse {
  phone: string | null
  email: string | null
  website: string | null
  contactPersonName: string | null
  contactPersonDesignation: string | null
  photoUrl: string | null
  bankName: string | null
  bankAccountMasked: string | null
  bankIfsc: string | null
  languagesOfWorship: string | null
  linkedInstitutions: string | null
  description: string | null
  annualFestivals: string | null
  landmark: string | null
  historicalSignificance: string | null
}

/** Shape mirrors backend TempleFullProfileResponse */
export interface TempleFullProfileResponse {
  temple: {
    id: number
    registrationNumber: string | null
    name: string
    aliasName: string | null
    grade: string
    primaryDeity: string | null
    tradition: string
    yearEstablished: number | null
    history: string | null
    doorNumber: string | null
    street: string | null
    villageTown: string | null
    pinCode: string | null
    hobliId: number | null
    talukId: number | null
    districtId: number | null
    cityId: number | null
    districtName: string | null
    latitude: number | null
    longitude: number | null
    contactName: string | null
    contactDesignation: string | null
    contactMobile: string | null
    contactEmail: string | null
    photoUrl: string | null
    website: string | null
    languagesOfWorship: string | null
    linkedInstitutions: string | null
    annualFestivals: string | null
    landmark: string | null
    historicalSignificance: string | null
    bankName: string | null
    bankIfsc: string | null
    trustRegistered: boolean
    assetDeclarationStatus: string | null
    status: string | null
    verificationStatus?: 'UNVERIFIED' | 'UNDER_REVIEW' | 'VERIFIED' | 'FLAGGED'
    /** Reason provided when DC flagged the temple. Null when not flagged. */
    dcFlagReason?: string | null
  }
  hobliName: string | null
  talukName: string | null
  districtName: string | null
  cityName: string | null
  trust: DcTrustSummary | null
  boardMembers: {
    current: BoardMemberSummary[]
    past: BoardMemberSummary[]
    validationIssues: string[]
  }
  trustFinancials: TrustFinancialSummary[]
  boardMeetings: BoardMeetingSummary[]
  employees: EmployeeSummary[]
  contractors: ContractorResponse[]
  declarations: DeclarationSummary[]
  currentProfile: ProfileCurrentResponse | null
  /**
   * Most recent profile staging record (any status).
   * Null when the temple has never submitted a profile.
   * Used to distinguish "no submissions yet" from "recently rejected".
   */
  latestProfileStaging?: {
    stagingId: number
    /** Canonical workflow status: SUBMITTED, APPROVED, REJECTED, DRAFT, etc. */
    status: string
    reviewComment: string | null
    versionNumber: number
    reviewedAt: string | null
  } | null
}

export interface BoardMeetingSummary {
  id: number
  meetingDate: string
  agenda: string | null
  minutesDocumentId: number | null
  createdAt: string
}

export interface BoardMemberSummary {
  id: number
  fullName: string
  designation: string
  contactNumber: string | null
  maskedAadhaar: string | null
  appointmentDate: string | null
  tenureEndDate: string | null
  address: string | null
  /** JSON wire key is "current" — matches backend @JsonProperty("current") on boolean field. */
  current: boolean
}

export interface EmployeeSummary {
  id: number
  templeId: number
  employeeRef: string | null
  fullName: string
  employeeType: string
  designation: string
  dateOfJoining: string | null
  salaryGrade: string | null
  mobile: string | null
  address: string | null
  status: string
  isHereditary: boolean
}

export interface ContractorResponse {
  id: number
  templeId: number
  companyName: string
  gstNumber: string | null
  serviceType: 'CIVIL_WORKS' | 'ELECTRICAL' | 'SECURITY' | 'CATERING' | 'EVENTS' | 'OTHER'
  contractReference: string | null
  workOrderDate: string | null
  contractStartDate: string | null
  contractEndDate: string | null
  contractValue: number | null
  paymentStatus: 'PENDING' | 'COMPLETED' | 'DISPUTED'
  documentIds?: number[]
}

export interface DeclarationSummary {
  id: number
  financialYear: string
  versionNumber: number
  status: DeclarationStatus
  submittedAt: string | null
  acknowledgementNumber: string | null
  agriculturalLandValue: number | null
  buildingsValue: number | null
  financialAssetsValue: number | null
  otherMovableValue: number | null
  dueDate: string | null
}

export interface ProfileStagingResponse {
  id: number
  templeId: number
  version: number
  status: string
  /** Canonical governance status — single source of truth. Use allowedActions to gate buttons. */
  governanceStatus?: import('@/types/workflow').GovernanceStatusPayload
  contactPersonName: string | null
  contactPersonDesignation: string | null
  phone: string | null
  email: string | null
  website: string | null
  photoUrl: string | null
  bankName: string | null
  bankAccountNumberMasked: string | null
  bankIfsc: string | null
  languagesOfWorship: string | null
  linkedInstitutions: string | null
  description: string | null
  annualFestivals: string | null
  landmark: string | null
  historicalSignificance: string | null
  // Identity fields (V93)
  aliasName: string | null
  primaryDeity: string | null
  grade: string | null
  tradition: string | null
  hobliId: number | null
  talukId: number | null
  addressLine1: string | null
  pinCode: string | null
  latitude: number | null
  longitude: number | null
  yearEstablished: number | null
  submittedAt: string
  submittedBy: number
  reviewedAt: string | null
  reviewedBy: number | null
  reviewComment: string | null
}

export interface ExportJobResponse {
  jobId: string
  format: ExportFormat
  status: 'SYNC_COMPLETE' | 'ASYNC_ACCEPTED'
  downloadUrl: string | null
  recordCount: number
}

export interface NotificationResponse {
  id: number
  title: string
  body: string
  referenceType: string | null
  referenceId: number | null
  read: boolean
  readAt: string | null
  createdAt: string
}

/** A single entry in the DC-side temple profile version history. */
export interface DcProfileHistoryEntry {
  stagingId: number
  versionNumber: number
  /** Canonical workflow status: APPROVED, REJECTED, SUBMITTED, RESUBMITTED, etc. */
  status: string
  submittedAt: string | null
  submittedBy: number | null
  reviewedAt: string | null
  reviewedBy: number | null
  reviewComment: string | null
}
