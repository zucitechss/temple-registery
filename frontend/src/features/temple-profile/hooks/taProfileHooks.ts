import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { toast } from 'sonner'
import { useGetCurrentUserQuery } from '@/features/auth/authApi'
import { useGetTaluksQuery, useGetHoblisQuery } from '@/features/geo/geoApi'
import { submitTempleProfileSchema, TaProfileStagingFormValues, TaProfileStagingRequest, taProfileStagingSchema, TaProfileStatus, TempleProfileStagingResponse } from '@/features/temple-profile/hooks/templeTypes'
import { useCreateOrUpdateDraftMutation, useGetActiveStagingQuery, useGetStagingHistoryQuery, useGetTempleByIdQuery, useSubmitForReviewMutation } from '@/features/temple-profile/hooks/templeApi'


// ─── Helpers ──────────────────────────────────────────────────────────────────

function deriveProfileStatus(
  stagingStatus: string | undefined | null,
  latestVersionStatus: string | undefined | null,
  checklistStatus: string | null | undefined,
  hasStagingProfile: boolean,
): TaProfileStatus {
  const mapStatus = (status?: string | null): TaProfileStatus | null => {
    if (!status) return null
    if (status === 'DRAFT') return 'DRAFT'
    // Backend labels PENDING_REVIEW as SUBMITTED in response (DECISION-01)
    if (status === 'PENDING_REVIEW' || status === 'SUBMITTED') return 'SUBMITTED'
    if (status === 'REJECTED') return 'REJECTED'
    if (status === 'APPROVED' || status === 'RE_APPROVED') return 'APPROVED'
    if (status === 'FLAGGED') return 'FLAGGED'
    if (status === 'UPDATED_AFTER_APPROVAL') return 'UPDATED_AFTER_APPROVAL'
    if (status === 'RESUBMITTED') return 'RESUBMITTED'
    return null
  }

  if (hasStagingProfile) {
    const mappedStagingStatus = mapStatus(stagingStatus)
    if (mappedStagingStatus) return mappedStagingStatus
  }

  // If no active staging exists, derive from the latest version in history.
  const mappedLatestVersionStatus = mapStatus(latestVersionStatus)
  if (mappedLatestVersionStatus) return mappedLatestVersionStatus

  if (checklistStatus === 'APPROVED') return 'APPROVED'
  if (checklistStatus === 'FLAGGED') return 'FLAGGED'
  if (checklistStatus === 'SUBMITTED' || checklistStatus === 'PENDING_REVIEW') return 'SUBMITTED'
  return 'NOT_STARTED'
}

function normalizeTagList(value?: string | string[] | null): string {
  if (!value) return ''
  if (Array.isArray(value)) return value.join(', ')
  if (value.startsWith('[')) {
    try {
      const parsed = JSON.parse(value)
      if (Array.isArray(parsed)) {
        return parsed.filter(Boolean).join(', ')
      }
    } catch {
      return value
    }
  }
  return value
}

function normalizeTagArray(value?: string | string[] | null): string[] | undefined {
  if (!value) return undefined
  if (Array.isArray(value)) return value.filter(Boolean)
  if (value.startsWith('[')) {
    try {
      const parsed = JSON.parse(value)
      if (Array.isArray(parsed)) return parsed.filter(Boolean)
    } catch {
      // fall through
    }
  }
  return value
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
}

function cleanOptional(value?: string | null): string | undefined {
  if (value == null) return undefined
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : undefined
}

function serializeTagList(value?: string | null): string | undefined {
  const cleaned = cleanOptional(value)
  if (!cleaned) return undefined
  const tokens = cleaned.split(',').map((s) => s.trim()).filter(Boolean)
  return tokens.length > 0 ? JSON.stringify(tokens) : undefined
}

function extractApiMessage(err: any, fallback: string): string {
  const validationMessage = err?.data?.errors?.[0]
  if (typeof validationMessage === 'string') return validationMessage
  return err?.data?.message || err?.message || fallback
}

function readProfileField(source: any, primary: string, fallback?: string): string | undefined {
  const primaryValue = source?.[primary]
  if (typeof primaryValue === 'string' && primaryValue.trim().length > 0) return primaryValue
  const fallbackValue = fallback ? source?.[fallback] : undefined
  if (typeof fallbackValue === 'string' && fallbackValue.trim().length > 0) return fallbackValue
  return undefined
}

const EMPTY_FORM: TaProfileStagingFormValues = {
  phone: '',
  email: '',
  website: '',
  contactPersonName: '',
  contactPersonDesignation: '',
  languagesOfWorship: '',
  linkedInstitutions: '',
  description: '',
  annualFestivals: '',
}

// ─── useTempleProfile ─────────────────────────────────────────────────────────

export function useTempleProfile() {
  const { data: userData, isLoading: userLoading } = useGetCurrentUserQuery()
  const user = userData?.data
  const templeId = user?.templeId

  const { data: templeData, isLoading: templeLoading, isError } = useGetTempleByIdQuery(
    templeId!, { skip: !templeId, refetchOnFocus: true, refetchOnMountOrArgChange: true },
  )
  const temple = templeData?.data ?? null

  const { data: stagingData, isLoading: stagingLoading } = useGetActiveStagingQuery(
    templeId!, { skip: !templeId, refetchOnMountOrArgChange: true, refetchOnFocus: true },
  )
  const stagingProfile = stagingData?.data ?? null
  const { data: historyData, isLoading: historyLoading } = useGetStagingHistoryQuery(
    { templeId: templeId!, page: 0, size: 20 },
    { skip: !templeId, refetchOnMountOrArgChange: true },
  )
  const history = historyData?.data?.content ?? []
  const latestVersion = history.reduce<TempleProfileStagingResponse | null>((latest, item) => {
    if (!latest) return item
    return item.versionNumber > latest.versionNumber ? item : latest
  }, null)

  // ── Geo name resolution ────────────────────────────────────────────────────
  // For display in overview: resolve taluk/hobli names from temple entity first,
  // Prefer staging values so the review page shows the TA's pending edits.
  const effectiveTalukId = stagingProfile?.talukId ?? temple?.talukId ?? undefined
  const effectiveHobliId = stagingProfile?.hobliId ?? temple?.hobliId ?? undefined

  const { data: taluksData } = useGetTaluksQuery(
    temple?.districtId!, { skip: !temple?.districtId },
  )
  const talukName = taluksData?.data?.find(t => t.id === effectiveTalukId)?.name

  const { data: hoblisData } = useGetHoblisQuery(
    effectiveTalukId!, { skip: !effectiveTalukId },
  )
  const hobliName = hoblisData?.data?.find(h => h.id === effectiveHobliId)?.name

  const [createOrUpdateDraft, { isLoading: isSaving }] = useCreateOrUpdateDraftMutation()
  const [submitForReview, { isLoading: isSubmitting }] = useSubmitForReviewMutation()

  /** True when the current user has EDIT access (not VIEW-only). */
  const canEdit = user?.accessType !== 'VIEW'

  const isLoading = userLoading || templeLoading || stagingLoading || historyLoading

  const profileStatus = deriveProfileStatus(
    stagingProfile?.statusLabel,
    latestVersion?.statusLabel,
    user?.completionChecklist?.templeProfileStatus,
    stagingProfile !== null,
  )
  const profileReviewComment =
    profileStatus === 'REJECTED'
      ? (stagingProfile?.reviewComment ?? latestVersion?.reviewComment ?? null)
      : null

  // Only allow editing if DRAFT or REJECTED (staging), otherwise edits go to main profile
  const isEditable = profileStatus === 'DRAFT' || profileStatus === 'REJECTED'
    || profileStatus === 'FLAGGED' || profileStatus === 'UPDATED_AFTER_APPROVAL'

  const form = useForm<TaProfileStagingFormValues>({
    resolver: zodResolver(taProfileStagingSchema),
    defaultValues: EMPTY_FORM,
    mode: 'onChange',
  })

  // Prefill logic: DRAFT/REJECTED use staging; otherwise use temple (main table).
  useEffect(() => {
    if (isLoading) return
    let source: any = null
    if (profileStatus === 'DRAFT' || profileStatus === 'REJECTED' || profileStatus === 'UPDATED_AFTER_APPROVAL') {
      source = stagingProfile
    } else {
      source = temple ?? stagingProfile
    }
    if (source) {
      const parsedLinked = normalizeTagList(source.linkedInstitutions)
      const parsedLanguages = normalizeTagList(source.languagesOfWorship)

      // Normalize phone: strip +91 or leading 0 so it's always a plain 10-digit number.
      const rawPhone = readProfileField(source, 'phone', 'contactMobile') ?? ''
      const normalizedPhone = rawPhone.replace(/^\+91/, '').replace(/^0/, '').trim()

      form.reset({
        phone: normalizedPhone,
        email: readProfileField(source, 'email', 'contactEmail') ?? '',
        website: readProfileField(source, 'website') ?? '',
        contactPersonName: readProfileField(source, 'contactPersonName', 'contactName') ?? '',
        contactPersonDesignation: readProfileField(source, 'contactPersonDesignation', 'contactDesignation') ?? '',
        languagesOfWorship: parsedLanguages,
        linkedInstitutions: parsedLinked,
        description: source.description ?? '',
        annualFestivals: source.annualFestivals ?? '',
        landmark: (source as any).landmark ?? '',
        historicalSignificance: (source as any).historicalSignificance ?? '',
        bankName: (source as any).bankName ?? '',
        bankIfsc: (source as any).bankIfsc ?? '',
        photoFilePath: (source as any).photoFilePath ?? (source as any).photoUrl ?? temple?.photoUrl ?? '',
        bankAccountNumber: '',
        // Identity fields (V93) — prefer staging values, fall back to temple entity
        aliasName: (source as any).aliasName ?? temple?.aliasName ?? '',
        primaryDeity: [(source as any).primaryDeity, temple?.primaryDeity].map(v => (!v || v === 'To be updated') ? '' : v).find(v => v) ?? '',
        grade: (source as any).grade ?? temple?.grade ?? undefined,
        tradition: (source as any).tradition ?? temple?.tradition ?? undefined,
        hobliId: (source as any).hobliId ?? temple?.hobliId ?? undefined,
        talukId: (source as any).talukId ?? temple?.talukId ?? undefined,
        addressLine1: (source as any).addressLine1 ?? temple?.street ?? '',
        pinCode: (source as any).pinCode ?? temple?.pinCode ?? '',
        latitude: (source as any).latitude ?? (temple?.latitude as any) ?? null,
        longitude: (source as any).longitude ?? (temple?.longitude as any) ?? null,
        placeId: (source as any).placeId ?? null,
        formattedAddress: (source as any).formattedAddress ?? null,
        yearEstablished: (source as any).yearEstablished ?? temple?.yearEstablished ?? null,
      })
    } else if (temple) {
      const parsedLanguages = normalizeTagList(temple.languagesOfWorship)
      const rawPhone = temple.contactMobile ?? ''
      const normalizedPhone = rawPhone.replace(/^\+91/, '').replace(/^0/, '').trim()

      form.reset({
        phone: normalizedPhone,
        email: temple.contactEmail ?? '',
        website: '',
        contactPersonName: temple.contactName ?? '',
        contactPersonDesignation: temple.contactDesignation ?? '',
        languagesOfWorship: parsedLanguages,
        linkedInstitutions: '',
        description: '',
        annualFestivals: '',
        landmark: temple.landmark ?? '',
        historicalSignificance: '',
        bankName: '',
        bankIfsc: '',
        photoFilePath: temple.photoUrl ?? '',
        bankAccountNumber: '',
        // Identity fields (V93)
        aliasName: temple.aliasName ?? '',
        primaryDeity: (!temple.primaryDeity || temple.primaryDeity === 'To be updated') ? '' : temple.primaryDeity,
        grade: temple.grade ?? undefined,
        tradition: temple.tradition ?? undefined,
        hobliId: temple.hobliId ?? undefined,
        talukId: temple.talukId ?? undefined,
        addressLine1: temple.street ?? '',
        pinCode: temple.pinCode ?? '',
        latitude: (temple.latitude as any) ?? null,
        longitude: (temple.longitude as any) ?? null,
        placeId: (temple as any).placeId ?? null,
        formattedAddress: (temple as any).formattedAddress ?? null,
        yearEstablished: temple.yearEstablished ?? null,
      })
    }
    // Only run when loading transitions to complete
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isLoading])

  // ── Handlers ───────────────────────────────────────────────────────────────

  const handleSave = async (data: TaProfileStagingFormValues) => {
    if (!templeId) return
    try {
      // Backend expects string fields; strip empty values so optional validation
      // does not fail on empty-string payloads.
      const body: TaProfileStagingRequest = {
        phone: cleanOptional(data.phone),
        email: cleanOptional(data.email),
        website: cleanOptional(data.website),
        contactPersonName: cleanOptional(data.contactPersonName),
        contactPersonDesignation: cleanOptional(data.contactPersonDesignation),
        photoFilePath: cleanOptional(data.photoFilePath),
        bankAccountNumber: cleanOptional(data.bankAccountNumber),
        bankName: cleanOptional(data.bankName),
        bankIfsc: cleanOptional(data.bankIfsc)?.toUpperCase(),
        languagesOfWorship: serializeTagList(data.languagesOfWorship),
        linkedInstitutions: serializeTagList(data.linkedInstitutions),
        description: cleanOptional(data.description),
        annualFestivals: cleanOptional(data.annualFestivals),
        landmark: cleanOptional(data.landmark),
        historicalSignificance: cleanOptional(data.historicalSignificance),
        // Identity fields (V93)
        aliasName: cleanOptional(data.aliasName),
        primaryDeity: cleanOptional(data.primaryDeity),
        grade: data.grade ?? undefined,
        tradition: data.tradition ?? undefined,
        hobliId: data.hobliId ?? undefined,
        talukId: data.talukId ?? undefined,
        addressLine1: cleanOptional(data.addressLine1),
        pinCode: cleanOptional(data.pinCode),
        latitude: data.latitude ?? undefined,
        longitude: data.longitude ?? undefined,
        placeId: data.placeId ?? undefined,
        formattedAddress: data.formattedAddress ?? undefined,
        yearEstablished: data.yearEstablished ?? undefined,
      }

      await createOrUpdateDraft({ templeId, body }).unwrap()
      form.reset(data)
      toast.success('Draft saved successfully.')
    } catch (err: any) {
      const message = extractApiMessage(err, 'Failed to save draft. Please try again.')
      toast.error(message)
    }
  }

  const handleSubmit = async () => {
    if (!templeId) return

    if (form.formState.isDirty) {
      toast.warning('Please save the draft before submitting.')
      return
    }

    const result = submitTempleProfileSchema.safeParse(form.getValues())
    if (!result.success) {
      const message = result.error.errors[0]?.message ?? 'Please fill in all required fields.'
      toast.error(message)
      return
    }

    try {
      await submitForReview(templeId).unwrap()
      toast.success('Profile submitted for DC review.')
    } catch (err: any) {
      const message = extractApiMessage(err, 'Failed to submit profile. Please try again.')
      toast.error(message)
    }
  }

  const handleDeleteDraft = async () => {
    // TODO: Backend does not expose DELETE /temples/{templeId}/profile/staging yet.
    // When the endpoint is added, call the mutation here and invalidate TempleStaging tag.
    toast.info('Please contact the administrator to discard this draft.')
  }

  const handleStartEdit = async () => {
    if (!templeId) return
    try {
      const prefill: TaProfileStagingRequest = {
        phone: cleanOptional(readProfileField(temple, 'phone', 'contactMobile')),
        email: cleanOptional(readProfileField(temple, 'email', 'contactEmail')),
        website: cleanOptional(readProfileField(temple, 'website')),
        contactPersonName: cleanOptional(readProfileField(temple, 'contactPersonName', 'contactName')),
        contactPersonDesignation: cleanOptional(readProfileField(temple, 'contactPersonDesignation', 'contactDesignation')),
        languagesOfWorship: serializeTagList(normalizeTagArray(temple?.languagesOfWorship)?.join(', ')),
        linkedInstitutions: serializeTagList(normalizeTagArray((temple as any)?.linkedInstitutions)?.join(', ')),
        description: cleanOptional((temple as any)?.description ?? temple?.history),
        annualFestivals: cleanOptional((temple as any)?.annualFestivals),
        landmark: cleanOptional(temple?.landmark),
        historicalSignificance: cleanOptional(temple?.historicalSignificance),
        bankName: cleanOptional(temple?.bankName),
        bankIfsc: cleanOptional(temple?.bankIfsc),
        // Identity fields (V93)
        aliasName: cleanOptional(temple?.aliasName),
        primaryDeity: cleanOptional(temple?.primaryDeity),
        grade: temple?.grade ?? undefined,
        tradition: temple?.tradition ?? undefined,
        hobliId: temple?.hobliId ?? undefined,
        talukId: temple?.talukId ?? undefined,
        addressLine1: cleanOptional(temple?.street),
        pinCode: cleanOptional(temple?.pinCode),
        latitude: (temple?.latitude as any) ?? undefined,
        longitude: (temple?.longitude as any) ?? undefined,
        placeId: (temple as any)?.placeId ?? undefined,
        formattedAddress: (temple as any)?.formattedAddress ?? undefined,
        yearEstablished: temple?.yearEstablished ?? undefined,
      }
      await createOrUpdateDraft({ templeId, body: prefill }).unwrap()
      toast.success('Edit mode activated. A new draft has been created.')
    } catch (err: any) {
      toast.error(extractApiMessage(err, 'Failed to start edit. Please try again.'))
    }
  }

  return {
    profileStatus,
    profileReviewComment,
    temple,
    talukName,
    hobliName,
    stagingProfile,
    currentProfile: latestVersion,
    isLoading,
    isError,
    isIniting: false,
    isSaving,
    isSubmitting,
    isDeleting: false, // TODO: update when backend DELETE endpoint is ready
    isEditable,
    canEdit,
    form,
    handleSave,
    handleSubmit,
    handleDeleteDraft,
    handleStartEdit,
  }
}

// ─── useProfileHistory ────────────────────────────────────────────────────────

export function useProfileHistory(enabled: boolean) {
  const { data: userData } = useGetCurrentUserQuery()
  const templeId = userData?.data?.templeId

  return useGetStagingHistoryQuery(
    { templeId: templeId!, page: 0, size: 20 },
    { skip: !templeId || !enabled },
  )
}