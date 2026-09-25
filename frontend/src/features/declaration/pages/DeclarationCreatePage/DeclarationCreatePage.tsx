import { useEffect, useMemo, useRef, useState } from 'react'
import { FormProvider, useFieldArray, useForm, useFormContext, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { toast } from 'sonner'
import { extractApiErrorMessage } from '@/lib/apiError'
import { CheckCircle2, ChevronRight, Paperclip, Plus, Save, Send, Sparkles, Trash2 } from 'lucide-react'
import {
  createDeclarationSchema,
  type CreateDeclarationRequest,
  type CompleteDeclarationResponse,
} from '../../declarationTypes'
import { getAvailableActions } from '../../declarationPermissions'
import {
  useCreateDeclarationMutation,
  useGetDeclarationQuery,
  useSubmitDeclarationMutation,
  useUpdateDeclarationMutation,
} from '../../declarationApi'
import { useUploadDocumentMutation } from '@/features/document/documentApi'
import { useAppSelector } from '@/app/store'
import { ROUTE_PATHS } from '@/constants/routePaths'
import { DeclarationStatusBadge } from '@/components/data-display/StatusBadge/StatusBadge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Separator } from '@/components/ui/separator'
import { cn } from '@/lib/utils'

function getCurrentFinancialYear(): string {
  const now = new Date()
  const startFY = now.getMonth() + 1 >= 4 ? now.getFullYear() : now.getFullYear() - 1
  return `${startFY}-${String(startFY + 1).slice(2)}`
}

const EMPTY_VALUES: CreateDeclarationRequest = {
  financialYear: getCurrentFinancialYear(),
  dueDate: '',
  annualIncome: undefined,
  annualExpenditure: undefined,
  agriculturalLands: [],
  buildings: [],
  leasedProperties: [],
  otherLands: [],
  preciousMetals: [],
  artifacts: [],
  vehicles: [],
  equipment: [],
  financialAssets: [],
}

interface DeclarationCreatePageProps {
  onAfterSubmit?: (id: number) => void
}

export function DeclarationCreatePage({ onAfterSubmit }: DeclarationCreatePageProps = {}) {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const templeId = useAppSelector((state) => state.auth.currentUser?.templeId) ?? Number(searchParams.get('templeId'))
  const declarationId = Number(searchParams.get('id') ?? '')
  const isEditMode = Number.isFinite(declarationId) && declarationId > 0

  const declarationQuery = useGetDeclarationQuery(declarationId, { skip: !isEditMode })
  const declaration = declarationQuery.data?.data
  // Use getAvailableActions to determine editability: a declaration is editable if the TA can edit it
  const editable = !isEditMode || (declaration
    ? getAvailableActions(declaration.status, 'TEMPLE_AUTHORITY').canEdit
    : true)

  const form = useForm<CreateDeclarationRequest>({
    resolver: zodResolver(createDeclarationSchema),
    defaultValues: EMPTY_VALUES,
    mode: 'onBlur',
  })

  useEffect(() => {
    if (!declaration) return
    form.reset(mapDeclarationToForm(declaration))
  }, [declaration, form])

  const [activeStep, setActiveStep] = useState(0)
  const [createDeclaration, { isLoading: creating }] = useCreateDeclarationMutation()
  const [updateDeclaration, { isLoading: updating }] = useUpdateDeclarationMutation()
  const [submitDeclaration, { isLoading: submitting }] = useSubmitDeclarationMutation()
  const [uploadDocument, { isLoading: uploading }] = useUploadDocumentMutation()

  const watched = useWatch({ control: form.control })
  const currentDeclarationId = isEditMode ? declarationId : declaration?.id ?? null

  const summary = useMemo(() => buildSummary(watched as CreateDeclarationRequest), [watched])

  const persistDeclaration = async (values: CreateDeclarationRequest) => {
    if (!templeId) {
      toast.error('Temple ID is missing for this account.')
      return null
    }

    if (isEditMode) {
      const response = await updateDeclaration({ id: declarationId, body: values }).unwrap()
      return response.data?.id ?? declarationId
    }

    const response = await createDeclaration({ templeId, body: values }).unwrap()
    const id = response.data?.id
    if (id) {
      setSearchParams({ id: String(id), templeId: String(templeId) }, { replace: true })
    }
    return id ?? null
  }

  const handleSaveDraft = form.handleSubmit(async (values) => {
    try {
      const id = await persistDeclaration(values)
      if (id) {
        toast.success('Draft saved successfully.')
      }
    } catch (err) {
      toast.error(extractApiErrorMessage(err, 'Failed to save draft.'))
    }
  })

  const handleSubmitForReview = form.handleSubmit(async (values) => {
    try {
      const id = await persistDeclaration(values)
      if (!id) return
      await submitDeclaration(id).unwrap()
      toast.success('Declaration submitted for DC review.')
      if (onAfterSubmit) {
        onAfterSubmit(id)
      } else {
        navigate(ROUTE_PATHS.TA_DECLARATION_DETAIL.replace(':id', String(id)))
      }
    } catch (err) {
      toast.error(extractApiErrorMessage(err, 'Failed to submit declaration.'))
    }
  })

  const handlePdfUpload = async (file: File, index: number) => {
    if (!currentDeclarationId) {
      toast.error('Save the draft before uploading the lease agreement PDF.')
      return
    }

    if (file.type !== 'application/pdf') {
      toast.error('Only PDF files are allowed.')
      return
    }

    if (file.size > 10 * 1024 * 1024) {
      toast.error('PDF size must be 10 MB or smaller.')
      return
    }

    const body = new FormData()
    body.append('ownerType', 'ASSET_DECLARATION')
    body.append('ownerId', String(currentDeclarationId))
    body.append('label', 'LEASE_AGREEMENT')
    body.append('file', file)

    try {
      const result = await uploadDocument(body).unwrap()
      const documentId = result.data?.id
      if (documentId) {
        form.setValue(`leasedProperties.${index}.agreementDocumentId`, documentId, {
          shouldDirty: true,
          shouldValidate: true,
        })
        toast.success('Lease agreement PDF uploaded.')
      }
    } catch (err) {
      toast.error(extractApiErrorMessage(err, 'Failed to upload PDF.'))
    }
  }

  if (!templeId) {
    return (
      <Card className="mx-auto max-w-2xl border-border/60 bg-card/90 shadow-soft-lg">
        <CardHeader>
          <CardTitle>Temple ID required</CardTitle>
          <CardDescription>We could not determine the temple for this session.</CardDescription>
        </CardHeader>
        <CardContent>
          <Button variant="outline" onClick={() => navigate(-1)}>Go back</Button>
        </CardContent>
      </Card>
    )
  }

  return (
    <FormProvider {...form}>
      <Form {...form}>
        <form className="space-y-6 pb-10">
          <HeroPanel
            isEditMode={isEditMode}
            editable={editable}
            declaration={declaration}
            summary={summary}
            activeStep={activeStep}
            onStepChange={setActiveStep}
          />

          {!editable && declaration?.status && (
            <Card className="border-border/60 bg-muted/40">
              <CardContent className="pt-6 text-sm text-muted-foreground">
                This declaration is locked because it is currently <span className="font-medium text-foreground">{declaration.status}</span>.
                Use the detail page for review or resubmission.
              </CardContent>
            </Card>
          )}

          {activeStep === 0 && <ImmovableStep declarationId={currentDeclarationId} />}
          {activeStep === 1 && <MovableStep declarationId={currentDeclarationId} onLeasePdfUpload={handlePdfUpload} />}
          {activeStep === 2 && <ReviewStep declaration={declaration} summary={summary} />}

          <Card className="border-border/60 bg-card/95 shadow-soft-lg">
            <CardContent className="flex flex-col gap-3 pt-6 sm:flex-row sm:items-center sm:justify-between">
              <div className="text-sm text-muted-foreground">
                Use draft save to preserve work. Lease agreement PDFs can be attached after the draft exists.
              </div>
              <div className="flex flex-wrap gap-2">
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => setActiveStep((step) => Math.max(step - 1, 0))}
                  disabled={activeStep === 0}
                >
                  Previous
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => setActiveStep((step) => Math.min(step + 1, 2))}
                  disabled={activeStep === 2}
                >
                  Next
                </Button>
                <Button type="button" variant="outline" onClick={handleSaveDraft} disabled={creating || updating || submitting || uploading}>
                  <Save size={16} className="mr-2" />
                  {creating || updating ? 'Saving...' : 'Save Draft'}
                </Button>
                <Button type="button" className="bg-gradient-gold shadow-gold" onClick={handleSubmitForReview} disabled={creating || updating || submitting || uploading}>
                  <Send size={16} className="mr-2" />
                  {submitting ? 'Submitting...' : 'Submit for Approval'}
                </Button>
                <Button type="button" variant="ghost" onClick={() => navigate(-1)}>
                  Cancel
                </Button>
              </div>
            </CardContent>
          </Card>
        </form>
      </Form>
    </FormProvider>
  )
}

function HeroPanel({
  isEditMode,
  editable,
  declaration,
  summary,
  activeStep,
  onStepChange,
}: {
  isEditMode: boolean
  editable: boolean
  declaration?: CompleteDeclarationResponse
  summary: SummaryState
  activeStep: number
  onStepChange: (step: number) => void
}) {
  const steps = [
    { label: 'Immovable Assets', hint: 'Land, buildings, leases' },
    { label: 'Movable Assets', hint: 'Jewels, vehicles, financials' },
    { label: 'Review & Submit', hint: 'Validate before DC review' },
  ]

  return (
    <Card className="overflow-hidden border-border/60 bg-gradient-to-br from-primary/8 via-card to-secondary/10 shadow-soft-xl">
      <CardContent className="space-y-6 p-6">
        <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
          <div className="space-y-3">
            <div className="inline-flex items-center gap-2 rounded-full border border-border/60 bg-background/70 px-3 py-1 text-xs font-medium text-muted-foreground">
              <Sparkles size={14} />
              Asset Declaration Studio
            </div>
            <div>
              <h1 className="text-3xl font-semibold tracking-tight text-foreground">
                {isEditMode ? 'Update declaration' : 'Create a new declaration'}
              </h1>
              <p className="mt-2 max-w-3xl text-sm text-muted-foreground">
                Multi-step filing for immovable and movable assets with draft, upload, and submission controls for the temple authority.
              </p>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <DeclarationStatusBadge status={(declaration?.status ?? 'DRAFT') as any} isOverdue={declaration?.isOverdue} />
              {declaration?.financialYear && (
                <span className="rounded-full border border-border/60 bg-background/80 px-3 py-1 text-xs text-muted-foreground">
                  FY {declaration.financialYear}
                </span>
              )}
              <span className="rounded-full border border-border/60 bg-background/80 px-3 py-1 text-xs text-muted-foreground">
                {editable ? 'Editable draft' : 'Read-only declaration'}
              </span>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3 lg:w-[360px]">
            <MiniStat label="Asset rows" value={summary.totalRows} />
            <MiniStat label="PDF links" value={summary.pdfLinks} />
            <MiniStat label="Estimated value" value={summary.estimatedValueLabel} />
            <MiniStat label="Current step" value={`${activeStep + 1}/3`} />
          </div>
        </div>

        <div className="grid gap-3 md:grid-cols-3">
          {steps.map((step, index) => (
            <button
              key={step.label}
              type="button"
              onClick={() => onStepChange(index)}
              className={cn(
                'rounded-2xl border p-4 text-left transition-all duration-200 hover:-translate-y-0.5',
                activeStep === index ? 'border-primary/30 bg-primary/5 shadow-soft-md' : 'border-border/60 bg-background/70',
              )}
            >
              <div className="flex items-start justify-between gap-3">
                <div>
                  <p className="text-sm font-semibold text-foreground">{step.label}</p>
                  <p className="mt-1 text-xs text-muted-foreground">{step.hint}</p>
                </div>
                <ChevronRight size={16} className={cn('mt-0.5', activeStep === index ? 'text-primary' : 'text-muted-foreground/50')} />
              </div>
            </button>
          ))}
        </div>
      </CardContent>
    </Card>
  )
}

function ImmovableStep({ declarationId }: { declarationId: number | null }) {
  return (
    <div className="space-y-4">
      <StepHeader title="Step 1" subtitle="Capture all immovable holdings and lease references." />
      <FormGrid>
        <FormInput name="financialYear" label="Financial year" placeholder="2025-26" disabled />
        <FormInput name="dueDate" label="Due date" type="date" />
        <FormMoney name="annualIncome" label="Annual income" />
        <FormMoney name="annualExpenditure" label="Annual expenditure" />
      </FormGrid>

      <ArraySection
        name="agriculturalLands"
        title="Agricultural Land"
        description="Survey number, village, acres, ownership, and patta status."
        addLabel="Add agricultural land"
        blankItem={{ surveyNumber: '', village: '', areaAcres: undefined, ownerOfRecord: '', pattaStatus: '' }}
        declarationId={declarationId}
        renderRow={(index, remove) => (
          <RowCard index={index} onRemove={remove} label="Agricultural parcel">
            <FormGrid>
              <FormInput name={`agriculturalLands.${index}.surveyNumber`} label="Survey number" />
              <FormInput name={`agriculturalLands.${index}.village`} label="Village" />
              <FormNumber name={`agriculturalLands.${index}.areaAcres`} label="Area (acres)" step="0.01" />
              <FormInput name={`agriculturalLands.${index}.ownerOfRecord`} label="Owner of record" />
              <FormInput name={`agriculturalLands.${index}.pattaStatus`} label="Patta status" />
            </FormGrid>
          </RowCard>
        )}
      />

      <ArraySection
        name="buildings"
        title="Temple Buildings"
        description="Buildings and complexes with location, age, structure, and valuation."
        addLabel="Add building"
        blankItem={{ location: '', totalAreaSqft: undefined, yearBuilt: undefined, structureType: '', valuationInr: undefined }}
        declarationId={declarationId}
        renderRow={(index, remove) => (
          <RowCard index={index} onRemove={remove} label="Building">
            <FormGrid>
              <FormInput name={`buildings.${index}.location`} label="Location" />
              <FormNumber name={`buildings.${index}.totalAreaSqft`} label="Total area (sq ft)" step="0.01" />
              <FormNumber name={`buildings.${index}.yearBuilt`} label="Year built" step="1" />
              <FormInput name={`buildings.${index}.structureType`} label="Structure type" />
              <FormMoney name={`buildings.${index}.valuationInr`} label="Valuation (INR)" />
            </FormGrid>
          </RowCard>
        )}
      />

      <ArraySection
        name="leasedProperties"
        title="Leased Properties"
        description="Attach the lease agreement PDF after the draft is saved."
        addLabel="Add leased property"
        blankItem={{ propertyAddress: '', lesseeName: '', leaseStartDate: '', leaseEndDate: '', monthlyRent: undefined, agreementDocumentId: undefined }}
        declarationId={declarationId}
        renderRow={(index, remove) => (
          <RowCard index={index} onRemove={remove} label="Leased property">
            <FormGrid>
              <FormInput name={`leasedProperties.${index}.propertyAddress`} label="Property address" />
              <FormInput name={`leasedProperties.${index}.lesseeName`} label="Lessee name" />
              <FormInput name={`leasedProperties.${index}.leaseStartDate`} label="Lease start date" type="date" />
              <FormInput name={`leasedProperties.${index}.leaseEndDate`} label="Lease end date" type="date" />
              <FormMoney name={`leasedProperties.${index}.monthlyRent`} label="Monthly rent" />
            </FormGrid>
            <LeaseUploader index={index} declarationId={declarationId} />
          </RowCard>
        )}
      />

      <ArraySection
        name="otherLands"
        title="Other Land Holdings"
        description="Land parcels outside the primary agricultural record."
        addLabel="Add other land"
        blankItem={{ location: '', area: undefined, usageType: '', revenueDepartmentReference: '' }}
        declarationId={declarationId}
        renderRow={(index, remove) => (
          <RowCard index={index} onRemove={remove} label="Other land">
            <FormGrid>
              <FormInput name={`otherLands.${index}.location`} label="Location" />
              <FormNumber name={`otherLands.${index}.area`} label="Area" step="0.01" />
              <FormInput name={`otherLands.${index}.usageType`} label="Usage type" />
              <FormInput name={`otherLands.${index}.revenueDepartmentReference`} label="Revenue department reference" />
            </FormGrid>
          </RowCard>
        )}
      />
    </div>
  )
}

function MovableStep({
  declarationId,
  onLeasePdfUpload,
}: {
  declarationId: number | null
  onLeasePdfUpload: (file: File, index: number) => Promise<void>
}) {
  return (
    <div className="space-y-4">
      <StepHeader title="Step 2" subtitle="Document all movable holdings and financial instruments." />

      <ArraySection
        name="preciousMetals"
        title="Gold & Silver Items"
        description="Description, weight, purity, and approximate value."
        addLabel="Add precious metal"
        blankItem={{ itemDescription: '', metalType: '', weightGrams: undefined, purity: '', approximateValueInr: undefined }}
        declarationId={declarationId}
        renderRow={(index, remove) => (
          <RowCard index={index} onRemove={remove} label="Precious metal">
            <FormGrid>
              <FormInput name={`preciousMetals.${index}.itemDescription`} label="Item description" />
              <FormInput name={`preciousMetals.${index}.metalType`} label="Metal type" placeholder="Gold / Silver" />
              <FormNumber name={`preciousMetals.${index}.weightGrams`} label="Weight (grams)" step="0.001" />
              <FormInput name={`preciousMetals.${index}.purity`} label="Purity" />
              <FormMoney name={`preciousMetals.${index}.approximateValueInr`} label="Approximate value" />
            </FormGrid>
          </RowCard>
        )}
      />

      <ArraySection
        name="artifacts"
        title="Idols & Sacred Artifacts"
        description="Material, period, provenance, and museum-grade classification."
        addLabel="Add artifact"
        blankItem={{ itemDescription: '', material: '', ageOrPeriod: '', provenance: '', museumGradeClassification: '', approximateValueInr: undefined }}
        declarationId={declarationId}
        renderRow={(index, remove) => (
          <RowCard index={index} onRemove={remove} label="Artifact">
            <FormGrid>
              <FormInput name={`artifacts.${index}.itemDescription`} label="Item description" />
              <FormInput name={`artifacts.${index}.material`} label="Material" />
              <FormInput name={`artifacts.${index}.ageOrPeriod`} label="Age / period" />
              <FormInput name={`artifacts.${index}.provenance`} label="Provenance" />
              <FormInput name={`artifacts.${index}.museumGradeClassification`} label="Museum-grade classification" />
              <FormMoney name={`artifacts.${index}.approximateValueInr`} label="Approximate value" />
            </FormGrid>
          </RowCard>
        )}
      />

      <ArraySection
        name="vehicles"
        title="Vehicles"
        description="Registration number, make/model, year, and purpose."
        addLabel="Add vehicle"
        blankItem={{ registrationNumber: '', makeModel: '', year: undefined, purpose: '' }}
        declarationId={declarationId}
        renderRow={(index, remove) => (
          <RowCard index={index} onRemove={remove} label="Vehicle">
            <FormGrid>
              <FormInput name={`vehicles.${index}.registrationNumber`} label="Registration number" />
              <FormInput name={`vehicles.${index}.makeModel`} label="Make / model" />
              <FormNumber name={`vehicles.${index}.year`} label="Year" step="1" />
              <FormInput name={`vehicles.${index}.purpose`} label="Purpose" />
            </FormGrid>
          </RowCard>
        )}
      />

      <ArraySection
        name="equipment"
        title="Electronic & Office Equipment"
        description="Item name, serial number, and approximate value."
        addLabel="Add equipment"
        blankItem={{ itemName: '', serialNumber: '', approximateValueInr: undefined }}
        declarationId={declarationId}
        renderRow={(index, remove) => (
          <RowCard index={index} onRemove={remove} label="Equipment">
            <FormGrid>
              <FormInput name={`equipment.${index}.itemName`} label="Item name" />
              <FormInput name={`equipment.${index}.serialNumber`} label="Serial number" />
              <FormMoney name={`equipment.${index}.approximateValueInr`} label="Approximate value" />
            </FormGrid>
          </RowCard>
        )}
      />

      <ArraySection
        name="financialAssets"
        title="Financial Assets"
        description="Fixed deposits and investments with maturity dates where relevant."
        addLabel="Add financial asset"
        blankItem={{ assetSubtype: '', bankName: '', investmentType: '', amount: undefined, maturityDate: '' }}
        declarationId={declarationId}
        renderRow={(index, remove) => (
          <RowCard index={index} onRemove={remove} label="Financial asset">
            <FormGrid>
              <FormInput name={`financialAssets.${index}.assetSubtype`} label="Subtype" placeholder="FIXED_DEPOSIT / INVESTMENT" />
              <FormInput name={`financialAssets.${index}.bankName`} label="Bank name" />
              <FormInput name={`financialAssets.${index}.investmentType`} label="Investment type" />
              <FormMoney name={`financialAssets.${index}.amount`} label="Amount" />
              <FormInput name={`financialAssets.${index}.maturityDate`} label="Maturity date" type="date" />
            </FormGrid>
          </RowCard>
        )}
      />

      <Card className="border-border/60 bg-card/95">
        <CardContent className="pt-6 text-sm text-muted-foreground">
          If you are attaching a lease agreement PDF, save the draft first so the upload can be linked to this declaration.
        </CardContent>
      </Card>
    </div>
  )
}

function ReviewStep({
  declaration,
  summary,
}: {
  declaration?: CompleteDeclarationResponse
  summary: SummaryState
}) {
  return (
    <div className="space-y-4">
      <StepHeader title="Step 3" subtitle="Review the declaration before submitting it to the DC office." />
      <div className="grid gap-4 lg:grid-cols-2">
        <Card className="border-border/60 bg-card/95">
          <CardHeader>
            <CardTitle className="text-base">Summary</CardTitle>
            <CardDescription>High-level filing stats for quick verification.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <SummaryRow label="Financial year" value={declaration?.financialYear ?? 'Not set'} />
            <SummaryRow label="Due date" value={declaration?.dueDate ?? 'Not set'} />
            <SummaryRow label="Immovable rows" value={String(summary.immovableRows)} />
            <SummaryRow label="Movable rows" value={String(summary.movableRows)} />
            <SummaryRow label="Estimated value" value={summary.estimatedValueLabel} />
          </CardContent>
        </Card>

        <Card className="border-border/60 bg-card/95">
          <CardHeader>
            <CardTitle className="text-base">Status guide</CardTitle>
            <CardDescription>What happens after submission.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3 text-sm text-muted-foreground">
            <p>Drafts can be edited and re-saved until they are submitted.</p>
            <p>Submitted declarations move to <span className="font-medium text-foreground">PENDING_REVIEW</span> for DC review.</p>
            <p>Approved declarations receive a digital acknowledgement and a version stamp.</p>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}

function ArraySection({
  name,
  title,
  description,
  addLabel,
  blankItem,
  renderRow,
  declarationId,
}: {
  name: keyof CreateDeclarationRequest
  title: string
  description: string
  addLabel: string
  blankItem: Record<string, unknown>
  declarationId: number | null
  renderRow: (index: number, remove: () => void) => React.ReactNode
}) {
  const { control } = useFormContext<CreateDeclarationRequest>()
  const { fields, append, remove } = useFieldArray({ control, name: name as never })

  return (
    <Card className="border-border/60 bg-card/95 shadow-soft-md">
      <CardHeader className="flex-row items-start justify-between space-y-0 gap-4">
        <div>
          <CardTitle className="text-base">{title}</CardTitle>
          <CardDescription>{description}</CardDescription>
        </div>
        <Button type="button" variant="outline" size="sm" onClick={() => append(blankItem as never)}>
          <Plus size={16} className="mr-2" />
          {addLabel}
        </Button>
      </CardHeader>
      <CardContent className="space-y-4">
        {fields.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-border/80 bg-muted/30 p-6 text-sm text-muted-foreground">
            No entries yet. Add one or more rows for this section.
          </div>
        ) : (
          fields.map((field, index) => (
            <div key={field.id} className="space-y-4">
              {renderRow(index, () => remove(index))}
              {index < fields.length - 1 && <Separator />}
            </div>
          ))
        )}
        {name === 'leasedProperties' && declarationId == null && (
          <div className="rounded-2xl border border-amber-200/60 bg-amber-50/70 p-3 text-xs text-amber-900">
            Save the draft first to enable PDF upload for lease agreements.
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function RowCard({
  index,
  label,
  onRemove,
  children,
}: {
  index: number
  label: string
  onRemove: () => void
  children: React.ReactNode
}) {
  return (
    <div className="rounded-2xl border border-border/70 bg-background/70 p-4 shadow-sm">
      <div className="mb-4 flex items-center justify-between gap-3">
        <div>
          <p className="text-sm font-semibold text-foreground">{label} #{index + 1}</p>
          <p className="text-xs text-muted-foreground">Fill the line-item details below.</p>
        </div>
        <Button type="button" variant="ghost" size="sm" onClick={onRemove} className="text-destructive hover:text-destructive">
          <Trash2 size={15} className="mr-2" />
          Remove
        </Button>
      </div>
      {children}
    </div>
  )
}

function LeaseUploader({ index, declarationId }: { index: number; declarationId: number | null }) {
  const inputRef = useRef<HTMLInputElement | null>(null)
  const { setValue, watch } = useFormContext<CreateDeclarationRequest>()
  const [uploadDocument, { isLoading }] = useUploadDocumentMutation()
  const documentId = watch(`leasedProperties.${index}.agreementDocumentId`)

  return (
    <div className="mt-4 rounded-2xl border border-dashed border-border/80 bg-muted/20 p-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="space-y-1">
          <p className="text-sm font-medium text-foreground">Lease agreement PDF</p>
          <p className="text-xs text-muted-foreground">
            PDF only, up to 10 MB. {documentId ? `Linked document #${documentId}` : 'No document linked yet.'}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <input
            ref={inputRef}
            type="file"
            accept="application/pdf"
            className="hidden"
            onChange={async (event) => {
              const file = event.target.files?.[0]
              event.target.value = ''
              if (!file) return
              if (!declarationId) {
                toast.error('Save the draft before uploading a PDF.')
                return
              }
              if (file.type !== 'application/pdf') {
                toast.error('Only PDF files are allowed.')
                return
              }
              if (file.size > 10 * 1024 * 1024) {
                toast.error('PDF size must be 10 MB or smaller.')
                return
              }

              const body = new FormData()
              body.append('ownerType', 'ASSET_DECLARATION')
              body.append('ownerId', String(declarationId))
              body.append('label', 'LEASE_AGREEMENT')
              body.append('file', file)

              try {
                const response = await uploadDocument(body).unwrap()
                const nextId = response.data?.id
                if (nextId) {
                  setValue(`leasedProperties.${index}.agreementDocumentId`, nextId, { shouldDirty: true })
                  toast.success('Lease agreement uploaded.')
                }
              } catch (err) {
                toast.error(extractApiErrorMessage(err, 'Failed to upload the lease agreement.'))
              }
            }}
          />
          <Button type="button" variant="outline" size="sm" onClick={() => inputRef.current?.click()} disabled={isLoading}>
            <Paperclip size={15} className="mr-2" />
            {isLoading ? 'Uploading...' : 'Upload PDF'}
          </Button>
        </div>
      </div>
    </div>
  )
}

function StepHeader({ title, subtitle }: { title: string; subtitle: string }) {
  return (
    <div className="flex items-center gap-3">
      <div className="flex h-10 w-10 items-center justify-center rounded-2xl bg-primary/10 text-primary">
        <CheckCircle2 size={18} />
      </div>
      <div>
        <p className="text-sm font-semibold text-foreground">{title}</p>
        <p className="text-xs text-muted-foreground">{subtitle}</p>
      </div>
    </div>
  )
}

function FormGrid({ children }: { children: React.ReactNode }) {
  return <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">{children}</div>
}

function FormInput({
  name,
  label,
  type = 'text',
  placeholder,
  disabled = false,
}: {
  name: keyof CreateDeclarationRequest | string
  label: string
  type?: string
  placeholder?: string
  disabled?: boolean
}) {
  const { control } = useFormContext<CreateDeclarationRequest>()
  return (
    <FormField
      control={control}
      name={name as never}
      render={({ field }) => (
        <FormItem>
          <FormLabel>{label}</FormLabel>
          <FormControl>
            <Input {...field} type={type} placeholder={placeholder} value={field.value ?? ''} disabled={disabled} />
          </FormControl>
          <FormMessage />
        </FormItem>
      )}
    />
  )
}

function FormNumber({
  name,
  label,
  step = 'any',
}: {
  name: keyof CreateDeclarationRequest | string
  label: string
  step?: string
}) {
  const { control } = useFormContext<CreateDeclarationRequest>()
  return (
    <FormField
      control={control}
      name={name as never}
      render={({ field }) => (
        <FormItem>
          <FormLabel>{label}</FormLabel>
          <FormControl>
            <Input
              type="number"
              min={0}
              step={step}
              value={field.value ?? ''}
              onChange={(event) => {
                const next = event.target.value
                field.onChange(next === '' ? undefined : Number(next))
              }}
            />
          </FormControl>
          <FormMessage />
        </FormItem>
      )}
    />
  )
}

function FormMoney({ name, label }: { name: keyof CreateDeclarationRequest | string; label: string }) {
  return <FormNumber name={name} label={label} step="0.01" />
}

function SummaryRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-4 rounded-xl border border-border/60 bg-muted/20 px-4 py-3">
      <span className="text-xs uppercase tracking-wide text-muted-foreground">{label}</span>
      <span className="text-sm font-medium text-foreground">{value}</span>
    </div>
  )
}

function MiniStat({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="rounded-2xl border border-border/60 bg-background/80 p-4 shadow-soft-sm">
      <div className="text-[11px] uppercase tracking-wide text-muted-foreground">{label}</div>
      <div className="mt-2 text-lg font-semibold text-foreground">{value}</div>
    </div>
  )
}

function mapDeclarationToForm(declaration: CompleteDeclarationResponse): CreateDeclarationRequest {
  return {
    financialYear: declaration.financialYear || getCurrentFinancialYear(),
    dueDate: declaration.dueDate ?? '',
    annualIncome: declaration.annualIncome ?? undefined,
    annualExpenditure: declaration.annualExpenditure ?? undefined,
    agriculturalLands: declaration.agriculturalLands?.map((item) => ({
      id: item.id,
      surveyNumber: item.surveyNumber ?? '',
      village: item.village ?? '',
      areaAcres: item.areaAcres ?? undefined,
      ownerOfRecord: item.ownerOfRecord ?? '',
      pattaStatus: item.pattaStatus ?? '',
    })) ?? [],
    buildings: declaration.buildings?.map((item) => ({
      id: item.id,
      location: item.location ?? '',
      totalAreaSqft: item.totalAreaSqft ?? undefined,
      yearBuilt: item.yearBuilt ?? undefined,
      structureType: item.structureType ?? '',
      valuationInr: item.valuationInr ?? undefined,
    })) ?? [],
    leasedProperties: declaration.leasedProperties?.map((item) => ({
      id: item.id,
      propertyAddress: item.propertyAddress ?? '',
      lesseeName: item.lesseeName ?? '',
      leaseStartDate: item.leaseStartDate ?? '',
      leaseEndDate: item.leaseEndDate ?? '',
      monthlyRent: item.monthlyRent ?? undefined,
      agreementDocumentId: item.agreementDocumentId ?? undefined,
    })) ?? [],
    otherLands: declaration.otherLands?.map((item) => ({
      id: item.id,
      location: item.location ?? '',
      area: item.area ?? undefined,
      usageType: item.usageType ?? '',
      revenueDepartmentReference: item.revenueDepartmentReference ?? '',
    })) ?? [],
    preciousMetals: declaration.preciousMetals?.map((item) => ({
      id: item.id,
      itemDescription: item.itemDescription ?? '',
      metalType: item.metalType ?? '',
      weightGrams: item.weightGrams ?? undefined,
      purity: item.purity ?? '',
      approximateValueInr: item.approximateValueInr ?? undefined,
    })) ?? [],
    artifacts: declaration.artifacts?.map((item) => ({
      id: item.id,
      itemDescription: item.itemDescription ?? '',
      material: item.material ?? '',
      ageOrPeriod: item.ageOrPeriod ?? '',
      provenance: item.provenance ?? '',
      museumGradeClassification: item.museumGradeClassification ?? '',
      approximateValueInr: item.approximateValueInr ?? undefined,
    })) ?? [],
    vehicles: declaration.vehicles?.map((item) => ({
      id: item.id,
      registrationNumber: item.registrationNumber ?? '',
      makeModel: item.makeModel ?? '',
      year: item.year ?? undefined,
      purpose: item.purpose ?? '',
    })) ?? [],
    equipment: declaration.equipment?.map((item) => ({
      id: item.id,
      itemName: item.itemName ?? '',
      serialNumber: item.serialNumber ?? '',
      approximateValueInr: item.approximateValueInr ?? undefined,
    })) ?? [],
    financialAssets: declaration.financialAssets?.map((item) => ({
      id: item.id,
      assetSubtype: item.assetSubtype ?? '',
      bankName: item.bankName ?? '',
      investmentType: item.investmentType ?? '',
      amount: item.amount ?? undefined,
      maturityDate: item.maturityDate ?? '',
    })) ?? [],
  }
}

function buildSummary(values?: CreateDeclarationRequest): SummaryState {
  const totalRows =
    (values?.agriculturalLands?.length ?? 0) +
    (values?.buildings?.length ?? 0) +
    (values?.leasedProperties?.length ?? 0) +
    (values?.otherLands?.length ?? 0) +
    (values?.preciousMetals?.length ?? 0) +
    (values?.artifacts?.length ?? 0) +
    (values?.vehicles?.length ?? 0) +
    (values?.equipment?.length ?? 0) +
    (values?.financialAssets?.length ?? 0)

  const immovableRows =
    (values?.agriculturalLands?.length ?? 0) +
    (values?.buildings?.length ?? 0) +
    (values?.leasedProperties?.length ?? 0) +
    (values?.otherLands?.length ?? 0)

  const movableRows =
    (values?.preciousMetals?.length ?? 0) +
    (values?.artifacts?.length ?? 0) +
    (values?.vehicles?.length ?? 0) +
    (values?.equipment?.length ?? 0) +
    (values?.financialAssets?.length ?? 0)

  const estimatedValue =
    (values?.annualIncome ?? 0) +
    (values?.annualExpenditure ?? 0) +
    sum(values?.buildings?.map((item) => item.valuationInr)) +
    sum(values?.leasedProperties?.map((item) => item.monthlyRent)) +
    sum(values?.preciousMetals?.map((item) => item.approximateValueInr)) +
    sum(values?.artifacts?.map((item) => item.approximateValueInr)) +
    sum(values?.equipment?.map((item) => item.approximateValueInr)) +
    sum(values?.financialAssets?.map((item) => item.amount))

  return {
    totalRows,
    immovableRows,
    movableRows,
    pdfLinks: values?.leasedProperties?.filter((item) => item.agreementDocumentId).length ?? 0,
    estimatedValueLabel: new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(estimatedValue),
  }
}

function sum(values?: Array<number | null | undefined>) {
  return (values ?? []).reduce<number>((acc, value) => acc + (value ?? 0), 0)
}

interface SummaryState {
  totalRows: number
  immovableRows: number
  movableRows: number
  pdfLinks: number
  estimatedValueLabel: string
}
