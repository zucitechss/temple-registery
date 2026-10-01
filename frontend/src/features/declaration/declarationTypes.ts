import { z } from 'zod'

export const DECLARATION_STATUSES = [
  'DRAFT',
  'SUBMITTED',
  'UNDER_REVIEW',
  'CLARIFICATION_REQUIRED',
  'CLARIFICATION_RESPONDED',
  'RESUBMITTED',
  'SITE_VISIT_SCHEDULED',
  'SITE_VISIT_COMPLETED',
  'VERIFIED',
  'APPROVED',
  'REJECTED',
  'OVERDUE',
  'SUPERSEDED',
  'WITHDRAWN',
  'RE_APPROVED',
  'UPDATED_AFTER_APPROVAL',
] as const

export type DeclarationStatus = (typeof DECLARATION_STATUSES)[number]

/** Human-readable labels for each canonical status */
export const DECLARATION_STATUS_LABELS: Record<DeclarationStatus, string> = {
  DRAFT: 'Draft',
  SUBMITTED: 'Submitted',
  UNDER_REVIEW: 'Under Review',
  CLARIFICATION_REQUIRED: 'Clarification Required',
  CLARIFICATION_RESPONDED: 'Clarification Responded',
  RESUBMITTED: 'Resubmitted',
  SITE_VISIT_SCHEDULED: 'Site Visit Scheduled',
  SITE_VISIT_COMPLETED: 'Site Visit Completed',
  VERIFIED: 'Verified',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  OVERDUE: 'Overdue',
  SUPERSEDED: 'Superseded',
  WITHDRAWN: 'Withdrawn',
  RE_APPROVED: 'Re-Approved',
  UPDATED_AFTER_APPROVAL: 'Updated After Approval',
}

/** Tailwind badge color classes for each canonical status */
export const DECLARATION_STATUS_BADGE_CLASSES: Record<DeclarationStatus, string> = {
  DRAFT: 'bg-muted text-muted-foreground border-border',
  SUBMITTED: 'bg-blue-100 text-blue-800 border-blue-200',
  UNDER_REVIEW: 'bg-amber-100 text-amber-800 border-amber-200',
  CLARIFICATION_REQUIRED: 'bg-orange-100 text-orange-800 border-orange-200',
  CLARIFICATION_RESPONDED: 'bg-sky-100 text-sky-800 border-sky-200',
  RESUBMITTED: 'bg-sky-100 text-sky-800 border-sky-200',
  SITE_VISIT_SCHEDULED: 'bg-purple-100 text-purple-800 border-purple-200',
  SITE_VISIT_COMPLETED: 'bg-indigo-100 text-indigo-800 border-indigo-200',
  VERIFIED: 'bg-teal-100 text-teal-800 border-teal-200',
  APPROVED: 'bg-green-100 text-green-800 border-green-200',
  REJECTED: 'bg-red-100 text-red-800 border-red-200',
  OVERDUE: 'bg-red-200 text-red-900 border-red-300',
  SUPERSEDED: 'bg-gray-200 text-gray-700 border-gray-300',
  WITHDRAWN: 'bg-gray-100 text-gray-600 border-gray-200',
  RE_APPROVED: 'bg-green-200 text-green-900 border-green-300',
  UPDATED_AFTER_APPROVAL: 'bg-yellow-100 text-yellow-800 border-yellow-200',
}

const moneySchema = z.number().nonnegative().optional()
const textSchema = z.string().trim().max(1000).optional()
const longTextSchema = z.string().trim().max(2000).optional()

export const agriLandItemSchema = z.object({
  id: z.number().optional(),
  surveyNumber: z.string().trim().max(100).optional(),
  village: z.string().trim().max(255).optional(),
  areaAcres: z.number().nonnegative().optional(),
  ownerOfRecord: z.string().trim().max(255).optional(),
  pattaStatus: z.string().trim().max(100).optional(),
})

export const buildingItemSchema = z.object({
  id: z.number().optional(),
  location: z.string().trim().max(500).optional(),
  totalAreaSqft: z.number().nonnegative().optional(),
  yearBuilt: z.number().int().min(1000).max(3000).optional(),
  structureType: z.string().trim().max(100).optional(),
  valuationInr: z.number().nonnegative().optional(),
})

export const leasedPropertyItemSchema = z.object({
  id: z.number().optional(),
  propertyAddress: z.string().trim().max(500).optional(),
  lesseeName: z.string().trim().max(255).optional(),
  leaseStartDate: z.string().optional(),
  leaseEndDate: z.string().optional(),
  monthlyRent: z.number().nonnegative().optional(),
  agreementDocumentId: z.number().optional(),
})

export const otherLandItemSchema = z.object({
  id: z.number().optional(),
  location: z.string().trim().max(500).optional(),
  area: z.number().nonnegative().optional(),
  usageType: z.string().trim().max(200).optional(),
  revenueDepartmentReference: z.string().trim().max(255).optional(),
})

export const preciousMetalItemSchema = z.object({
  id: z.number().optional(),
  itemDescription: z.string().trim().max(500).optional(),
  metalType: z.string().trim().max(100).optional(),
  weightGrams: z.number().nonnegative().optional(),
  purity: z.string().trim().max(50).optional(),
  approximateValueInr: z.number().nonnegative().optional(),
})

export const artifactItemSchema = z.object({
  id: z.number().optional(),
  itemDescription: z.string().trim().max(500).optional(),
  material: z.string().trim().max(100).optional(),
  ageOrPeriod: z.string().trim().max(100).optional(),
  provenance: z.string().trim().max(1000).optional(),
  museumGradeClassification: z.string().trim().max(100).optional(),
  approximateValueInr: z.number().nonnegative().optional(),
})

export const vehicleItemSchema = z.object({
  id: z.number().optional(),
  registrationNumber: z.string().trim().max(100).optional(),
  makeModel: z.string().trim().max(255).optional(),
  year: z.number().int().min(1900).max(3000).optional(),
  purpose: z.string().trim().max(255).optional(),
  approximateValueInr: z.number().nonnegative().optional(),
})

export const equipmentItemSchema = z.object({
  id: z.number().optional(),
  itemName: z.string().trim().max(255).optional(),
  serialNumber: z.string().trim().max(100).optional(),
  approximateValueInr: z.number().nonnegative().optional(),
})

export const financialAssetItemSchema = z.object({
  id: z.number().optional(),
  assetSubtype: z.string().trim().max(100).optional(),
  bankName: z.string().trim().max(255).optional(),
  investmentType: z.string().trim().max(255).optional(),
  amount: z.number().nonnegative().optional(),
  maturityDate: z.string().optional(),
})

export const createDeclarationSchema = z.object({
  financialYear: z.string().min(4, 'Financial year is required'),
  dueDate: z.string().min(1, 'Due date is required'),
  annualIncome: moneySchema,
  annualExpenditure: moneySchema,
  agriculturalLands: z.array(agriLandItemSchema).default([]),
  buildings: z.array(buildingItemSchema).default([]),
  leasedProperties: z.array(leasedPropertyItemSchema).default([]),
  otherLands: z.array(otherLandItemSchema).default([]),
  preciousMetals: z.array(preciousMetalItemSchema).default([]),
  artifacts: z.array(artifactItemSchema).default([]),
  vehicles: z.array(vehicleItemSchema).default([]),
  equipment: z.array(equipmentItemSchema).default([]),
  financialAssets: z.array(financialAssetItemSchema).default([]),
})

export const clarificationSchema = z.object({
  message: z.string().min(1, 'Message is required').max(2000),
})

export const clarificationRespondSchema = z.object({
  message: z.string().min(1, 'Response is required').max(2000),
})

export const resubmitDeclarationSchema = createDeclarationSchema.extend({
  clarificationResponse: z.string().min(1, 'Response is required').max(2000),
})

export type CreateDeclarationRequest = z.infer<typeof createDeclarationSchema>
export type ClarificationRequest = z.infer<typeof clarificationSchema>
export type ClarificationRespondRequest = z.infer<typeof clarificationRespondSchema>
export type ResubmitDeclarationRequest = z.infer<typeof resubmitDeclarationSchema>

export interface AssetItemResponse {
  id: number
}

export interface AgriLandItemResponse extends AssetItemResponse {
  surveyNumber: string | null
  village: string | null
  areaAcres: number | null
  ownerOfRecord: string | null
  pattaStatus: string | null
}

export interface BuildingItemResponse extends AssetItemResponse {
  location: string | null
  totalAreaSqft: number | null
  yearBuilt: number | null
  structureType: string | null
  valuationInr: number | null
}

export interface LeasedPropertyItemResponse extends AssetItemResponse {
  propertyAddress: string | null
  lesseeName: string | null
  leaseStartDate: string | null
  leaseEndDate: string | null
  monthlyRent: number | null
  agreementDocumentId: number | null
}

export interface OtherLandItemResponse extends AssetItemResponse {
  location: string | null
  area: number | null
  usageType: string | null
  revenueDepartmentReference: string | null
}

export interface PreciousMetalItemResponse extends AssetItemResponse {
  itemDescription: string | null
  metalType: string | null
  weightGrams: number | null
  purity: string | null
  approximateValueInr: number | null
}

export interface ArtifactItemResponse extends AssetItemResponse {
  itemDescription: string | null
  material: string | null
  ageOrPeriod: string | null
  provenance: string | null
  museumGradeClassification: string | null
  approximateValueInr: number | null
}

export interface VehicleItemResponse extends AssetItemResponse {
  registrationNumber: string | null
  makeModel: string | null
  year: number | null
  purpose: string | null
  approximateValueInr: number | null
}

export interface EquipmentItemResponse extends AssetItemResponse {
  itemName: string | null
  serialNumber: string | null
  approximateValueInr: number | null
}

export interface FinancialAssetItemResponse extends AssetItemResponse {
  assetSubtype: string | null
  bankName: string | null
  investmentType: string | null
  amount: number | null
  maturityDate: string | null
}

export interface DeclarationVersionResponse {
  id: number
  versionNumber: number
  status: DeclarationStatus | null
  submittedAt: string | null
  reviewedAt: string | null
  acknowledgementNumber: string | null
  reviewedBy: number | null
  remarks: string | null
  createdAt: string
}

export interface DeclarationResponse {
  id: number
  templeId: number
  templeName?: string | null
  districtId: number
  financialYear?: string | null
  versionNumber?: number | null
  status: DeclarationStatus
  agriculturalLandAcres?: number | null
  agriculturalLandValue?: number | null
  buildingsSqft?: number | null
  buildingsValue?: number | null
  leasedPropertiesCount?: number | null
  leasedPropertiesValue?: number | null
  otherLandValue?: number | null
  goldGrams?: number | null
  silverGrams?: number | null
  idolsCount?: number | null
  vehiclesCount?: number | null
  financialAssetsValue?: number | null
  otherMovableValue?: number | null
  submittedAt?: string | null
  reviewedAt?: string | null
  reviewedBy?: number | null
  acknowledgementNumber?: string | null
  dueDate?: string | null
  overdue?: boolean | null
  isOverdue?: boolean | null
  overdueFlaggedAt?: string | null
  remarks?: string | null
  rejectionReason?: string | null
  annualIncome?: number | null
  annualExpenditure?: number | null
}

export interface CompleteDeclarationResponse extends DeclarationResponse {
  workflowInstanceId?: number | null
  acknowledgedAt?: string | null
  clarificationRound?: number | null
  agriculturalLands: AgriLandItemResponse[]
  buildings: BuildingItemResponse[]
  leasedProperties: LeasedPropertyItemResponse[]
  otherLands: OtherLandItemResponse[]
  preciousMetals: PreciousMetalItemResponse[]
  artifacts: ArtifactItemResponse[]
  vehicles: VehicleItemResponse[]
  equipment: EquipmentItemResponse[]
  financialAssets: FinancialAssetItemResponse[]
}

export interface AcknowledgementResponse {
  acknowledgementNumber: string
  downloadUrl: string
  generatedAt: string
}

export interface DeclarationDiffItem {
  field: string
  oldValue: string | null
  newValue: string | null
  category?: string
  fieldName?: string
}

export interface ClarificationThreadItem {
  id: number
  direction: 'DC_TO_TEMPLE' | 'TEMPLE_TO_DC'
  message: string
  authorId: number
  createdAt: string
}

export type CompleteDeclarationRequest = CreateDeclarationRequest

export type ChatMessageType = 'CLARIFICATION' | 'RESPONSE' | 'SITE_VISIT'
export type ChatActor = 'DC' | 'TA'

export interface ChatMessage {
  id: string
  type: ChatMessageType
  actor: ChatActor
  message: string
  timestamp: string   // ISO-8601
  metadata: string | null
}
