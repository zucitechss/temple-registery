import { useEffect, useRef, useState, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { toast } from 'sonner'
import { ArrowLeft, Save, SendHorizontal, Lock } from 'lucide-react'
import {
  Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
} from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Button } from '@/components/ui/button'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import { CardSkeleton } from '@/components/feedback/Skeleton/Skeleton'
import { EmptyState } from '@/components/feedback/EmptyState/EmptyState'
import { ROUTE_PATHS } from '@/constants/routePaths'
import { AccordionSection } from '@/features/temple-profile/pages/TaTempleEditPage/AccordianSection'
import { TagInputField } from '@/features/temple-profile/pages/TaTempleEditPage/TagInputField'
import { MultipleImageUpload } from '@/features/temple-profile/components/MultipleImageUpload'
import {
  taProfileStagingSchema,
  type TaProfileStagingFormValues,
  TEMPLE_GRADES,
  RELIGIOUS_TRADITIONS,
} from '@/features/temple-profile/hooks/templeTypes'
import {
  useCreateOrUpdateDraftMutation,
  useSubmitForReviewMutation,
} from '@/features/temple-profile/hooks/templeApi'
import { useDcTempleProfile, useDcPendingProfileStaging } from '@/features/dc/dcHooks'
import { dcApi } from '@/features/dc/dcApi'
import { extractApiErrorMessage } from '@/lib/apiError'
import { GeoHierarchySelectGrid } from '@/features/geo/components/GeoHierarchySelect/GeoHierarchySelectGrid'
import type { GeoSelection } from '@/features/geo/geoTypes'
import { TempleLocationPicker } from '@/features/temple-profile/components/TempleLocationPicker/TempleLocationPicker'
import { useAppDispatch } from '@/app/store'

const TRADITION_LABELS: Record<string, string> = {
  SHAIVITE: 'Shaivite',
  VAISHNAVITE: 'Vaishnavite',
  SHAKTA: 'Shakta',
  JAIN: 'Jain',
  BUDDHIST: 'Buddhist',
  OTHER: 'Other',
}

const normalizeToCommaString = (value: unknown): string => {
  if (!value) return ''
  if (Array.isArray(value)) return value.filter(Boolean).join(', ')
  if (typeof value === 'string') {
    if (value.startsWith('[')) {
      try {
        const parsed = JSON.parse(value)
        if (Array.isArray(parsed)) return parsed.filter(Boolean).join(', ')
      } catch {
        return value
      }
    }
    return value
  }
  return ''
}

/** Converts a comma-separated display string to a JSON array string for backend JSON columns. */
const serializeTagList = (value?: string | null): string | undefined => {
  if (!value) return undefined
  const trimmed = value.trim()
  if (!trimmed) return undefined
  const tokens = trimmed.split(',').map((s) => s.trim()).filter(Boolean)
  return tokens.length > 0 ? JSON.stringify(tokens) : undefined
}

export function SaTempleEditPage() {
  const { templeId: templeIdStr } = useParams<{ templeId: string }>()
  const navigate = useNavigate()
  const dispatch = useAppDispatch()
  const id = Number(templeIdStr)

  const { profile, isLoading, isError } = useDcTempleProfile(id)
  const { pendingStaging } = useDcPendingProfileStaging(id)

  const [createOrUpdateDraft, { isLoading: isSaving }] = useCreateOrUpdateDraftMutation()
  const [submitForReview, { isLoading: isSubmitting }] = useSubmitForReviewMutation()

  const initializedForTempleId = useRef<number | null>(null)
  // Separate geo ref: must NOT be blocked by isDirty because GeoHierarchySelectGrid
  // child effects call form.setValue({shouldDirty:true}) in the same effects flush,
  // which can set isDirty=true before the parent effect reads its guard on remount.
  const isGeoInitialized = useRef(false)

  const form = useForm<TaProfileStagingFormValues>({
    resolver: zodResolver(taProfileStagingSchema),
    defaultValues: {
      phone: '',
      email: '',
      website: '',
      contactPersonName: '',
      contactPersonDesignation: '',
      languagesOfWorship: '',
      linkedInstitutions: '',
      description: '',
      annualFestivals: '',
      landmark: '',
      historicalSignificance: '',
      bankAccountNumber: '',
      bankName: '',
      bankIfsc: '',
      photoFilePath: '',
      aliasName: '',
      primaryDeity: '',
      grade: undefined,
      tradition: undefined,
      hobliId: undefined,
      talukId: undefined,
      addressLine1: '',
      pinCode: '',
      latitude: null,
      longitude: null,
      yearEstablished: null,
    },
  })

  // Lazily initialise from cached data so taluks/hoblis queries are subscribed
  // on the very first render (second+ visit). Falls back to {} when data is not
  // yet cached, and the geo-init effect below handles that case.
  const [geoSelection, setGeoSelection] = useState<GeoSelection>(() => {
    if (profile?.temple) {
      const temple = profile.temple
      const staging = pendingStaging
      return {
        stateId: 1,
        cityId: temple.cityId ?? undefined,
        districtId: temple.districtId ?? undefined,
        talukId: staging?.talukId ?? temple.talukId ?? undefined,
        hobliId: staging?.hobliId ?? temple.hobliId ?? undefined,
      }
    }
    return {}
  })

  // Reset geo selection when navigating to a different temple
  useEffect(() => {
    setGeoSelection({})
    isGeoInitialized.current = false
  }, [id])

  // Dedicated geo initialization — no isDirty dependency.
  // Fires once per mount (or id change) as soon as profile data is ready.
  // Uses functional setState to skip the write when lazy init already ran.
  useEffect(() => {
    if (isGeoInitialized.current) return
    if (isLoading || !profile) return
    isGeoInitialized.current = true
    const temple = profile.temple
    const staging = pendingStaging
    setGeoSelection(prev => {
      if (prev.districtId !== undefined) return prev
      return {
        stateId: 1,
        cityId: temple.cityId ?? undefined,
        districtId: temple.districtId ?? undefined,
        talukId: staging?.talukId ?? temple.talukId ?? undefined,
        hobliId: staging?.hobliId ?? temple.hobliId ?? undefined,
      }
    })
  }, [isLoading, profile, pendingStaging, id])

  const handleGeoChange = useCallback((sel: GeoSelection) => {
    setGeoSelection(sel)
    if (sel.hobliId) {
      form.setValue('hobliId', sel.hobliId, { shouldDirty: true })
    }
    // Taluk must be submitted independently of hobli — a temple's location may not have
    // a Hobli in the master geo data yet, in which case this is the only geo selection
    // that can be made and saved.
    if (sel.talukId) {
      form.setValue('talukId', sel.talukId, { shouldDirty: true })
    }
  }, [form])

  const [detectingLocation, setDetectingLocation] = useState(false)

  const handleDetectLocation = useCallback(() => {
    if (!navigator.geolocation) {
      toast.error('Geolocation is not supported by your browser.')
      return
    }
    setDetectingLocation(true)
    navigator.geolocation.getCurrentPosition(
      (position) => {
        form.setValue('latitude', position.coords.latitude, { shouldDirty: true })
        form.setValue('longitude', position.coords.longitude, { shouldDirty: true })
        setDetectingLocation(false)
        toast.success('Location detected successfully.')
      },
      () => {
        setDetectingLocation(false)
        toast.error('Unable to detect location. Please allow location access and try again.')
      },
    )
  }, [form])

  // Prefill once per temple after data loads
  useEffect(() => {
    if (form.formState.isDirty || initializedForTempleId.current === id || isLoading || !profile) return

    const temple = profile.temple
    const staging = pendingStaging
    const current = profile.currentProfile

    // Priority: pendingStaging > currentProfile > temple entity
    const resolve = (stagingKey: string, currentKey: string, templeKey: string): string =>
      ((staging as any)?.[stagingKey] ?? (current as any)?.[currentKey] ?? (temple as any)?.[templeKey]) ?? ''

    const rawPhone = resolve('phone', 'phone', 'contactMobile')
    const normalizedPhone = String(rawPhone).replace(/^\+91/, '').replace(/^0/, '').trim()

    form.reset({
      phone: normalizedPhone,
      email: resolve('email', 'email', 'contactEmail'),
      website: resolve('website', 'website', 'website'),
      contactPersonName: resolve('contactPersonName', 'contactPersonName', 'contactName'),
      contactPersonDesignation: resolve('contactPersonDesignation', 'contactPersonDesignation', 'contactDesignation'),
      languagesOfWorship: normalizeToCommaString(
        staging?.languagesOfWorship ?? current?.languagesOfWorship ?? temple.languagesOfWorship
      ),
      linkedInstitutions: normalizeToCommaString(
        staging?.linkedInstitutions ?? (current as any)?.linkedInstitutions ?? temple.linkedInstitutions
      ),
      description: staging?.description ?? (current as any)?.description ?? temple.history ?? '',
      annualFestivals: resolve('annualFestivals', 'annualFestivals', 'annualFestivals'),
      landmark: resolve('landmark', 'landmark', 'landmark'),
      historicalSignificance: resolve('historicalSignificance', 'historicalSignificance', 'historicalSignificance'),
      bankName: resolve('bankName', 'bankName', 'bankName'),
      bankIfsc: resolve('bankIfsc', 'bankIfsc', 'bankIfsc'),
      bankAccountNumber: '',
      photoFilePath: (staging as any)?.photoUrl ?? (current as any)?.photoUrl ?? temple.photoUrl ?? '',
      aliasName: resolve('aliasName', 'aliasName', 'aliasName'),
      primaryDeity: resolve('primaryDeity', 'primaryDeity', 'primaryDeity'),
      grade: (staging?.grade ?? (current as any)?.grade ?? temple.grade) as any ?? undefined,
      tradition: (staging?.tradition ?? (current as any)?.tradition ?? temple.tradition) as any ?? undefined,
      hobliId: staging?.hobliId ?? temple.hobliId ?? undefined,
      talukId: staging?.talukId ?? temple.talukId ?? undefined,
      addressLine1: resolve('addressLine1', 'addressLine1', 'street'),
      pinCode: resolve('pinCode', 'pinCode', 'pinCode'),
      latitude: staging?.latitude ?? temple.latitude ?? null,
      longitude: staging?.longitude ?? temple.longitude ?? null,
      yearEstablished: staging?.yearEstablished ?? temple.yearEstablished ?? null,
    })

    initializedForTempleId.current = id
  }, [isLoading, profile, pendingStaging, form, form.formState.isDirty, id])

  const buildBody = (values: TaProfileStagingFormValues) => ({
    phone: values.phone || undefined,
    email: values.email || undefined,
    website: values.website || undefined,
    contactPersonName: values.contactPersonName || undefined,
    contactPersonDesignation: values.contactPersonDesignation || undefined,
    languagesOfWorship: serializeTagList(values.languagesOfWorship),
    linkedInstitutions: serializeTagList(values.linkedInstitutions),
    description: values.description || undefined,
    annualFestivals: values.annualFestivals || undefined,
    landmark: values.landmark || undefined,
    historicalSignificance: values.historicalSignificance || undefined,
    bankName: values.bankName || undefined,
    bankIfsc: values.bankIfsc || undefined,
    bankAccountNumber: values.bankAccountNumber || undefined,
    aliasName: values.aliasName || undefined,
    primaryDeity: values.primaryDeity || undefined,
    grade: values.grade || undefined,
    tradition: values.tradition || undefined,
    hobliId: values.hobliId || undefined,
    talukId: values.talukId || undefined,
    addressLine1: values.addressLine1 || undefined,
    pinCode: values.pinCode || undefined,
    latitude: values.latitude ?? undefined,
    longitude: values.longitude ?? undefined,
    yearEstablished: values.yearEstablished ?? undefined,
  })

  const onSaveDraft = form.handleSubmit(async (values) => {
    try {
      await createOrUpdateDraft({ templeId: id, body: buildBody(values) }).unwrap()
      dispatch(dcApi.util.invalidateTags([{ type: 'DcTempleProfile', id }, { type: 'DcProfileStaging', id }]))
      toast.success('Draft saved.')
      form.reset(values)
    } catch (err) {
      toast.error(extractApiErrorMessage(err, 'Failed to save draft.'))
    }
  })

  const onSaveAndSubmit = form.handleSubmit(async (values) => {
    try {
      await createOrUpdateDraft({ templeId: id, body: buildBody(values) }).unwrap()
      await submitForReview(id).unwrap()
      dispatch(dcApi.util.invalidateTags([{ type: 'DcTempleProfile', id }, { type: 'DcProfileStaging', id }]))
      toast.success('Profile saved and submitted for review.')
      navigate(ROUTE_PATHS.DC_TEMPLE_DETAIL.replace(':templeId', String(id)))
    } catch (err) {
      toast.error(extractApiErrorMessage(err, 'Failed to save and submit.'))
    }
  })

  if (isLoading) {
    return (
      <div className="space-y-4">
        <CardSkeleton />
        <CardSkeleton />
        <CardSkeleton />
      </div>
    )
  }

  if (isError || !profile) {
    return (
      <div className="flex flex-col items-center justify-center min-h-[60vh]">
        <EmptyState
          title="Temple not found"
          description="Unable to load temple profile."
          action={{ label: 'Go back', onClick: () => navigate(-1) }}
        />
      </div>
    )
  }

  const temple = profile.temple

  return (
    <Form {...form}>
      <form className="space-y-6 pb-8">

        {/* ── Page header ───────────────────────────────────────────────── */}
        <div className="flex items-center justify-between flex-wrap gap-3">
          <div className="flex items-center gap-3">
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="h-8 w-8 p-0"
              onClick={() => navigate(ROUTE_PATHS.DC_TEMPLE_DETAIL.replace(':templeId', String(id)))}
            >
              <ArrowLeft size={16} />
            </Button>
            <div>
              <h1 className="text-xl font-display font-bold text-foreground">Edit Temple Profile</h1>
              <p className="text-sm text-muted-foreground mt-0.5">
                {temple.name} · Changes will be submitted for review
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={isSaving || !form.formState.isDirty}
              className="gap-1.5"
              onClick={onSaveDraft}
            >
              <Save size={14} />
              {isSaving ? 'Saving…' : 'Save Draft'}
            </Button>
            <Button
              type="button"
              size="sm"
              disabled={isSubmitting || isSaving}
              className="bg-gradient-to-r from-primary to-accent text-primary-foreground shadow-sm gap-1.5"
              onClick={onSaveAndSubmit}
            >
              <SendHorizontal size={14} />
              {isSubmitting ? 'Submitting…' : 'Save & Submit for Review'}
            </Button>
          </div>
        </div>

        {/* ── Temple Location ───────────────────────────────────────────── */}
        <AccordionSection title="Temple Location">
          {temple.districtId ? (
            <>
              <div className="flex items-start gap-2 mb-4 rounded-lg border border-border/50 bg-muted/30 px-4 py-3">
                <Lock size={14} className="mt-0.5 shrink-0 text-muted-foreground" />
                <p className="text-sm text-muted-foreground">
                  State, city and district are fixed for this temple. You can update taluk and hobli below.
                </p>
              </div>
              <GeoHierarchySelectGrid
                key={String(temple.districtId)}
                value={geoSelection}
                onChange={handleGeoChange}
                lockedLevels={['city', 'district']}
                onDetectLocation={handleDetectLocation}
                detectingLocation={detectingLocation}
              />
            </>
          ) : (
            <GeoHierarchySelectGrid
              key="no-district"
              value={geoSelection}
              onChange={handleGeoChange}
              lockedLevels={['city', 'district']}
              onDetectLocation={handleDetectLocation}
              detectingLocation={detectingLocation}
            />
          )}

          <div className="space-y-4 mt-4">
            <TempleLocationPicker
              lat={form.watch('latitude') ?? null}
              lng={form.watch('longitude') ?? null}
              placeId={form.watch('placeId') ?? null}
              formattedAddress={form.watch('formattedAddress') ?? null}
              onChange={({ lat, lng, placeId: pid, formattedAddress: fa }) => {
                form.setValue('latitude', lat, { shouldDirty: true })
                form.setValue('longitude', lng, { shouldDirty: true })
                if (pid !== undefined) form.setValue('placeId', pid, { shouldDirty: true })
                if (fa !== undefined) form.setValue('formattedAddress', fa, { shouldDirty: true })
              }}
            />

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <FormField control={form.control} name="addressLine1" render={({ field }) => (
                <FormItem className="sm:col-span-2">
                  <FormLabel>Street / Address</FormLabel>
                  <FormControl>
                    <Input {...field} placeholder="Street address" />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )} />

              <FormField control={form.control} name="pinCode" render={({ field }) => (
                <FormItem>
                  <FormLabel>PIN Code</FormLabel>
                  <FormControl>
                    <Input {...field} placeholder="560001" maxLength={6} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )} />

              <FormField control={form.control} name="latitude" render={({ field }) => (
                <FormItem>
                  <FormLabel>Latitude</FormLabel>
                  <FormControl>
                    <Input
                      type="number"
                      step="0.0000001"
                      placeholder="12.9716"
                      value={field.value ?? ''}
                      onChange={e => field.onChange(e.target.value ? parseFloat(e.target.value) : null)}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )} />

              <FormField control={form.control} name="longitude" render={({ field }) => (
                <FormItem>
                  <FormLabel>Longitude</FormLabel>
                  <FormControl>
                    <Input
                      type="number"
                      step="0.0000001"
                      placeholder="77.5946"
                      value={field.value ?? ''}
                      onChange={e => field.onChange(e.target.value ? parseFloat(e.target.value) : null)}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )} />
            </div>
          </div>
        </AccordionSection>

        {/* ── Temple Identity ────────────────────────────────────────────── */}
        <AccordionSection title="Temple Identity">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <FormField control={form.control} name="primaryDeity" render={({ field }) => (
              <FormItem>
                <FormLabel>Primary Deity</FormLabel>
                <FormControl><Input {...field} placeholder="e.g. Chamundeshwari Devi" /></FormControl>
                <FormMessage />
              </FormItem>
            )} />

            <FormField control={form.control} name="aliasName" render={({ field }) => (
              <FormItem>
                <FormLabel>Alias / Local Name</FormLabel>
                <FormControl><Input {...field} placeholder="Optional alternate name" /></FormControl>
                <FormMessage />
              </FormItem>
            )} />

            <FormField control={form.control} name="grade" render={({ field }) => (
              <FormItem>
                <FormLabel>Temple Grade</FormLabel>
                <Select onValueChange={field.onChange} value={field.value ?? ''}>
                  <FormControl>
                    <SelectTrigger><SelectValue placeholder="Select grade" /></SelectTrigger>
                  </FormControl>
                  <SelectContent>
                    {TEMPLE_GRADES.map(g => (
                      <SelectItem key={g} value={g}>Grade {g}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )} />

            <FormField control={form.control} name="tradition" render={({ field }) => (
              <FormItem>
                <FormLabel>Religious Tradition</FormLabel>
                <Select onValueChange={field.onChange} value={field.value ?? ''}>
                  <FormControl>
                    <SelectTrigger><SelectValue placeholder="Select tradition" /></SelectTrigger>
                  </FormControl>
                  <SelectContent>
                    {RELIGIOUS_TRADITIONS.map(t => (
                      <SelectItem key={t} value={t}>{TRADITION_LABELS[t] ?? t}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )} />

            <FormField control={form.control} name="yearEstablished" render={({ field }) => (
              <FormItem>
                <FormLabel>Year Established</FormLabel>
                <FormControl>
                  <Input
                    type="number"
                    placeholder="e.g. 1200"
                    value={field.value ?? ''}
                    onChange={e => field.onChange(e.target.value ? parseInt(e.target.value, 10) : null)}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )} />
          </div>
        </AccordionSection>

        {/* ── About Temple ───────────────────────────────────────────────── */}
        <AccordionSection title="About Temple">
          <FormField control={form.control} name="description" render={({ field }) => (
            <FormItem>
              <FormLabel>Description</FormLabel>
              <FormControl>
                <Textarea {...field} placeholder="Brief description of the temple, its significance and history…" rows={4} className="resize-y" />
              </FormControl>
              <FormMessage />
            </FormItem>
          )} />

          <FormField control={form.control} name="historicalSignificance" render={({ field }) => (
            <FormItem>
              <FormLabel>Historical Significance</FormLabel>
              <FormControl>
                <Textarea {...field} placeholder="Details about myths, legends, or history associated with the temple…" rows={3} className="resize-y" />
              </FormControl>
              <FormMessage />
            </FormItem>
          )} />

          <FormField control={form.control} name="landmark" render={({ field }) => (
            <FormItem>
              <FormLabel>Landmark</FormLabel>
              <FormControl>
                <Input {...field} placeholder="e.g. Near Central Bus Stand" />
              </FormControl>
              <FormMessage />
            </FormItem>
          )} />

          <FormField control={form.control} name="annualFestivals" render={({ field }) => (
            <FormItem>
              <FormLabel>Annual Festivals</FormLabel>
              <FormControl>
                <Textarea {...field} placeholder="List the major annual festivals and their approximate dates…" rows={3} className="resize-y" />
              </FormControl>
              <FormMessage />
            </FormItem>
          )} />
        </AccordionSection>

        {/* ── Cultural Details ───────────────────────────────────────────── */}
        <AccordionSection title="Cultural Details">
          <FormField control={form.control} name="languagesOfWorship" render={({ field }) => (
            <FormItem>
              <FormLabel>Languages of Worship</FormLabel>
              <FormControl>
                <TagInputField
                  value={field.value ?? ''}
                  onChange={field.onChange}
                  placeholder="e.g. Kannada, Sanskrit"
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )} />

          <FormField control={form.control} name="linkedInstitutions" render={({ field }) => (
            <FormItem>
              <FormLabel>Linked Mutts / Sub-Temples / Institutions</FormLabel>
              <FormControl>
                <TagInputField
                  value={field.value ?? ''}
                  onChange={field.onChange}
                  placeholder="e.g. Sri Mutt, Sub-temple"
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )} />
        </AccordionSection>

        {/* ── Contact Information ────────────────────────────────────────── */}
        <AccordionSection title="Contact Information">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <FormField control={form.control} name="contactPersonName" render={({ field }) => (
              <FormItem>
                <FormLabel>Contact Person Name</FormLabel>
                <FormControl><Input {...field} placeholder="Full name" /></FormControl>
                <FormMessage />
              </FormItem>
            )} />

            <FormField control={form.control} name="contactPersonDesignation" render={({ field }) => (
              <FormItem>
                <FormLabel>Designation</FormLabel>
                <FormControl><Input {...field} placeholder="e.g. Head Priest, Manager" /></FormControl>
                <FormMessage />
              </FormItem>
            )} />

            <FormField control={form.control} name="phone" render={({ field }) => (
              <FormItem>
                <FormLabel>Phone (10-digit)</FormLabel>
                <FormControl>
                  <Input {...field} placeholder="9876543210" inputMode="tel" maxLength={10} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )} />

            <FormField control={form.control} name="email" render={({ field }) => (
              <FormItem>
                <FormLabel>Email</FormLabel>
                <FormControl>
                  <Input {...field} placeholder="contact@temple.org" type="email" />
                </FormControl>
                <FormMessage />
              </FormItem>
            )} />

            <FormField control={form.control} name="website" render={({ field }) => (
              <FormItem className="sm:col-span-2">
                <FormLabel>Website</FormLabel>
                <FormControl>
                  <Input {...field} placeholder="https://temple.org" type="url" />
                </FormControl>
                <FormMessage />
              </FormItem>
            )} />
          </div>
        </AccordionSection>

        {/* ── Bank Details ───────────────────────────────────────────────── */}
        <AccordionSection title="Bank Details (Hundi/Donation)">
          <p className="text-xs text-muted-foreground mb-4">
            Account numbers are kept encrypted. Leave Account Number empty to keep the existing one unchanged.
          </p>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <FormField control={form.control} name="bankName" render={({ field }) => (
              <FormItem>
                <FormLabel>Bank Name</FormLabel>
                <FormControl><Input {...field} placeholder="e.g. State Bank of India" /></FormControl>
                <FormMessage />
              </FormItem>
            )} />

            <FormField control={form.control} name="bankIfsc" render={({ field }) => (
              <FormItem>
                <FormLabel>Bank IFSC</FormLabel>
                <FormControl><Input {...field} placeholder="SBIN0001234" maxLength={11} /></FormControl>
                <FormMessage />
              </FormItem>
            )} />

            <FormField control={form.control} name="bankAccountNumber" render={({ field }) => (
              <FormItem className="sm:col-span-2">
                <FormLabel>Account Number</FormLabel>
                <FormControl>
                  <Input {...field} placeholder="Account Number" type="password" autoComplete="new-password" />
                </FormControl>
                <p className="text-xs text-muted-foreground mt-1">
                  {(pendingStaging as any)?.bankAccountNumberMasked
                    ? `Current: ${(pendingStaging as any).bankAccountNumberMasked} — leave empty to keep it unchanged.`
                    : (profile.currentProfile as any)?.bankAccountMasked
                    ? `Current: ${(profile.currentProfile as any).bankAccountMasked} — leave empty to keep it unchanged.`
                    : 'Leave empty to keep the existing account on record.'}
                </p>
                <FormMessage />
              </FormItem>
            )} />
          </div>
        </AccordionSection>

        {/* ── Temple Media ───────────────────────────────────────────────── */}
        <AccordionSection title="Temple Media">
          <p className="text-sm text-muted-foreground mb-2">
            Upload photos of the temple. The first image is automatically set as the primary photo.
          </p>
          {temple.id && (
            <MultipleImageUpload
              templeId={temple.id}
              onUploadSuccess={() => {}}
            />
          )}
        </AccordionSection>

        {/* ── Bottom action bar (mobile) ──────────────────────────────────── */}
        <div className="flex sm:hidden gap-3 pb-4">
          <Button
            type="button"
            variant="outline"
            className="flex-1 gap-1.5"
            disabled={isSaving || !form.formState.isDirty}
            onClick={onSaveDraft}
          >
            <Save size={14} />
            {isSaving ? 'Saving…' : 'Save Draft'}
          </Button>
          <Button
            type="button"
            className="flex-1 gap-1.5 bg-gradient-to-r from-primary to-accent text-primary-foreground"
            disabled={isSubmitting || isSaving}
            onClick={onSaveAndSubmit}
          >
            <SendHorizontal size={14} />
            {isSubmitting ? 'Submitting…' : 'Submit'}
          </Button>
        </div>

      </form>
    </Form>
  )
}
