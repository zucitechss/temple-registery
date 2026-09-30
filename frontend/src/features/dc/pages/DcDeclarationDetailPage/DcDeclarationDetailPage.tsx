import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import {
  ArrowLeft,
  BadgeCheck,
  FileText,
  HelpCircle,
  History,
  ShieldAlert,
  Sparkles,
  Stamp,
  XCircle,
} from 'lucide-react'
import { useAppSelector } from '@/app/store'
import { USER_ROLES } from '@/constants/roles'
import { ROUTE_PATHS } from '@/constants/routePaths'
import { cn } from '@/lib/utils'
import { EmptyState } from '@/components/feedback/EmptyState/EmptyState'
import { ReadOnlyBanner } from '@/components/feedback/ReadOnlyBanner/ReadOnlyBanner'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { StatusBadge } from '@/components/data-display/StatusBadge/StatusBadge'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Textarea } from '@/components/ui/textarea'
import {
  useDcDeclarationDetail,
  useWorkflowActions,
} from '@/features/dc/dcHooks'
import type { ClarificationItemResponse } from '@/features/dc/dcTypes'
import { normalizeDeclarationStatusForDisplay } from '@/features/dc/declarationStatusFilters'
import type { DeclarationVersionResponse } from '@/features/declaration/declarationTypes'
import {
  workflowApproveSchema,
  workflowRejectSchema,
} from '@/features/dc/dcTypes'
import { dcClarifySchema } from '@/features/governance/governanceTypes'
import { useGetDeclarationDiffQuery, useGetDeclarationVersionsQuery } from '@/features/declaration/declarationApi'
import { ChatPanel } from '@/features/declaration/components/ChatPanel'
import { DeclarationActionCardSkeleton } from '@/features/dc/components/DcSkeletons/DcSkeletons'

export function DcDeclarationDetailPage() {
  const { id: rawId } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const id = Number(rawId)
  const isValid = Number.isFinite(id) && id > 0

  const role = useAppSelector((state) => state.auth.currentUser?.role)
  const canAct = role === USER_ROLES.DISTRICT_COLLECTOR || role === USER_ROLES.SUPER_ADMIN

  const { declaration, isLoading, isError, isFetching } = useDcDeclarationDetail(id)
  const { dialog, openDialog, closeDialog, confirmApprove, confirmReject, confirmClarify, confirmScheduleSiteVisit, isSubmitting } = useWorkflowActions()

  const versionsQuery = useGetDeclarationVersionsQuery(id, { skip: !isValid })
  const versions = versionsQuery.data?.data ?? []
  const [compareVersion, setCompareVersion] = useState<number | undefined>(undefined)
  const diffQuery = useGetDeclarationDiffQuery(
    { id, compareToVersion: compareVersion },
    { skip: !isValid || !compareVersion },
  )
  const diff = diffQuery.data?.data ?? []

  useEffect(() => {
    if (!compareVersion && versions.length > 1) {
      setCompareVersion(versions[1].versionNumber)
    }
  }, [compareVersion, versions])

  const actionable = ['SUBMITTED', 'UNDER_REVIEW', 'CLARIFICATION_RESPONDED', 'SITE_VISIT_COMPLETED', 'VERIFIED'].includes(declaration?.status ?? '')

  if (isLoading) {
    return <DeclarationSkeleton />
  }

  if (isError || !declaration) {
    return (
      <EmptyState
        title="Declaration not found"
        description="We could not load the declaration for review."
        action={{ label: 'Go back', onClick: () => navigate(-1) }}
      />
    )
  }

  const chatStatus = normalizeDeclarationStatusForDisplay(declaration.status) ?? 'SUBMITTED'

  return (
    <div className="space-y-6 pb-8">
      {(role === USER_ROLES.AUDITOR || role === USER_ROLES.VIEWER) && (
        <ReadOnlyBanner message="You are viewing this declaration in read-only mode. Approval, rejection, and clarification actions are not available." />
      )}
      <Card className="overflow-hidden border-border/60 bg-gradient-to-br from-primary/8 via-card to-secondary/10 shadow-soft-xl">
        <CardContent className="space-y-5 p-6">
          <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
            <div className="space-y-3">
              <Button variant="ghost" size="sm" onClick={() => navigate(-1)} className="-ml-2 w-fit">
                <ArrowLeft size={16} className="mr-2" />
                Back
              </Button>
              <div className="inline-flex items-center gap-2 rounded-full border border-border/60 bg-background/80 px-3 py-1 text-xs font-medium text-muted-foreground">
                <Sparkles size={14} />
                District Collector Review
              </div>
              <div>
                <h1 className="text-3xl font-semibold tracking-tight text-foreground">
                  Declaration FY {declaration.financialYear}
                </h1>
                <p className="mt-2 text-sm text-muted-foreground">
                  Temple #{declaration.templeId} · Declaration #{declaration.id}
                </p>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <StatusBadge status={declaration.status} />
                <span className="rounded-full border border-border/60 bg-background/80 px-3 py-1 text-xs text-muted-foreground">
                  Version {declaration.versionNumber}
                </span>
                {declaration.overdue && (
                  <span className="rounded-full bg-orange-100 px-3 py-1 text-xs font-medium text-orange-800">Overdue</span>
                )}
                {declaration.acknowledgementNumber && (
                  <span className="rounded-full border border-border/60 bg-background/80 px-3 py-1 text-xs text-muted-foreground">
                    Ack {declaration.acknowledgementNumber}
                  </span>
                )}
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3 lg:w-[360px]">
              <MiniStat label="Immovable rows" value={(declaration.agriculturalLands?.length ?? 0) + (declaration.buildings?.length ?? 0) + (declaration.leasedProperties?.length ?? 0) + (declaration.otherLands?.length ?? 0)} />
              <MiniStat label="Movable rows" value={(declaration.preciousMetals?.length ?? 0) + (declaration.artifacts?.length ?? 0) + (declaration.vehicles?.length ?? 0) + (declaration.equipment?.length ?? 0)} />
              <MiniStat label="Clarifications" value={declaration.clarifications?.length ?? 0} />
              <MiniStat label="PDF leases" value={declaration.leasedProperties?.filter((item) => item.agreementDocumentId).length ?? 0} />
            </div>
          </div>
        </CardContent>
      </Card>

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_360px]">
        <div className="space-y-6">
          <Tabs defaultValue="overview">
            <TabsList className="grid w-full grid-cols-4 lg:w-auto">
              <TabsTrigger value="overview">Overview</TabsTrigger>
              <TabsTrigger value="assets">Assets</TabsTrigger>
              <TabsTrigger value="history">History</TabsTrigger>
              <TabsTrigger value="diff">Compare</TabsTrigger>
            </TabsList>

            <TabsContent value="overview" className="mt-6 space-y-4">
              <div className="grid gap-4 lg:grid-cols-2">
                <Card className="border-border/60 bg-card/95 shadow-soft-md">
                  <CardHeader>
                    <CardTitle className="text-base">Workflow summary</CardTitle>
                    <CardDescription>Current state of the declaration review.</CardDescription>
                  </CardHeader>
                  <CardContent className="space-y-3 text-sm">
                    <DetailRow label="Submitted" value={formatDate(declaration.submittedAt)} />
                    <DetailRow label="Reviewed" value={formatDate(declaration.reviewedAt)} />
                    <DetailRow label="Reviewed by" value={declaration.reviewedBy ? `User #${declaration.reviewedBy}` : 'Not reviewed'} />
                    <DetailRow label="Remarks" value={declaration.remarks ?? 'No remarks'} />
                    <DetailRow label="Acknowledgement" value={declaration.acknowledgementNumber ?? 'Pending'} />
                  </CardContent>
                </Card>

                <Card className="border-border/60 bg-card/95 shadow-soft-md">
                  <CardHeader>
                    <CardTitle className="text-base">Headline values</CardTitle>
                    <CardDescription>Key totals used for the declaration comparison.</CardDescription>
                  </CardHeader>
                  <CardContent className="grid gap-3 sm:grid-cols-2">
                    <MetricBox label="Agricultural acres" value={declaration.agriculturalLandAcres} />
                    <MetricBox label="Buildings" value={declaration.buildingsSqft} unit="sq ft" />
                    <MetricBox label="Gold" value={declaration.goldGrams} unit="g" />
                    <MetricBox label="Financial assets" value={declaration.financialAssetsValue} money />
                  </CardContent>
                </Card>
              </div>

              <ChatPanel
                declarationId={declaration.id}
                declarationStatus={chatStatus}
                readonly={true}
              />
            </TabsContent>

            <TabsContent value="assets" className="mt-6 space-y-4">
              <AssetGroup title="Immovable Assets" icon={<FileText size={16} />}>
                <AssetSection title="Agricultural Land" items={declaration.agriculturalLands ?? []} renderItem={(item) => (
                  <AssetLine title={item.surveyNumber ?? 'Parcel'} subtitle={`${item.village ?? 'Village n/a'} · ${item.areaAcres ?? 0} acres`} value={`${item.ownerOfRecord ?? 'Owner n/a'} · ${item.pattaStatus ?? 'Patta n/a'}`} />
                )} />
                <AssetSection title="Buildings" items={declaration.buildings ?? []} renderItem={(item) => (
                  <AssetLine title={item.location ?? 'Building'} subtitle={`${item.totalAreaSqft ?? 0} sq ft · ${item.yearBuilt ?? 'Year n/a'}`} value={`${item.structureType ?? 'Structure n/a'} · ${formatCurrency(item.valuationInr ?? 0)}`} />
                )} />
                <AssetSection title="Leased Properties" items={declaration.leasedProperties ?? []} renderItem={(item) => (
                  <AssetLine title={item.propertyAddress ?? 'Leased property'} subtitle={`${item.lesseeName ?? 'Lessee n/a'} · ${formatDate(item.leaseStartDate)} to ${formatDate(item.leaseEndDate)}`} value={item.agreementDocumentId ? `Doc #${item.agreementDocumentId}` : 'No PDF'} />
                )} />
                <AssetSection title="Other Land Holdings" items={declaration.otherLands ?? []} renderItem={(item) => (
                  <AssetLine title={item.location ?? 'Other land'} subtitle={`${item.area ?? 0} units · ${item.usageType ?? 'Usage n/a'}`} value={item.revenueDepartmentReference ?? 'No reference'} />
                )} />
              </AssetGroup>

              <AssetGroup title="Movable Assets" icon={<History size={16} />}>
                <AssetSection title="Precious Metals" items={declaration.preciousMetals ?? []} renderItem={(item) => (
                  <AssetLine title={item.itemDescription ?? 'Metal item'} subtitle={`${item.metalType ?? 'Type n/a'} · ${item.weightGrams ?? 0} g · ${item.purity ?? 'Purity n/a'}`} value={formatCurrency(item.approximateValueInr ?? 0)} />
                )} />
                <AssetSection title="Artifacts" items={declaration.artifacts ?? []} renderItem={(item) => (
                  <AssetLine title={item.itemDescription ?? 'Artifact'} subtitle={`${item.material ?? 'Material n/a'} · ${item.ageOrPeriod ?? 'Age n/a'} · ${item.museumGradeClassification ?? item.provenance ?? 'No classification'}`} value={formatCurrency(item.estimatedValueInr ?? item.approximateValueInr ?? 0)} />
                )} />
                <AssetSection title="Vehicles" items={declaration.vehicles ?? []} renderItem={(item) => (
                  <AssetLine title={item.registrationNumber ?? 'Vehicle'} subtitle={`${item.makeModel ?? 'Model n/a'} · ${item.year ?? 'Year n/a'} · ${item.purpose ?? 'Purpose n/a'}`} value={formatCurrency(item.estimatedValueInr ?? 0)} />
                )} />
                <AssetSection title="Equipment" items={declaration.equipment ?? []} renderItem={(item) => (
                  <AssetLine title={item.itemName ?? 'Equipment'} subtitle={item.serialNumber ?? 'Serial n/a'} value={formatCurrency(item.estimatedValueInr ?? 0)} />
                )} />
              </AssetGroup>
            </TabsContent>

            <TabsContent value="history" className="mt-6">
              <Card className="border-border/60 bg-card/95 shadow-soft-md">
                <CardHeader>
                  <CardTitle className="text-base">Version history</CardTitle>
                  <CardDescription>Select a version to compare against the current declaration.</CardDescription>
                </CardHeader>
                <CardContent className="space-y-3">
                  {versions.map((version) => (
                    <VersionCard
                      key={version.id}
                      version={version}
                      selected={version.versionNumber === compareVersion}
                      onSelect={() => setCompareVersion(version.versionNumber)}
                    />
                  ))}
                </CardContent>
              </Card>
            </TabsContent>

            <TabsContent value="diff" className="mt-6">
              <Card className="border-border/60 bg-card/95 shadow-soft-md">
                <CardHeader className="flex-row items-start justify-between space-y-0 gap-3">
                  <div>
                    <CardTitle className="text-base">Compare versions</CardTitle>
                    <CardDescription>View field-level changes against a prior submission.</CardDescription>
                  </div>
                  <SelectVersion versions={versions} value={compareVersion} onChange={setCompareVersion} />
                </CardHeader>
                <CardContent>
                  {diffQuery.isLoading ? (
                    <div className="space-y-3">
                      {Array.from({ length: 4 }).map((_, index) => (
                        <div key={index} className="h-16 animate-pulse rounded-2xl bg-muted" />
                      ))}
                    </div>
                  ) : diff.length === 0 ? (
                    <EmptyState title="No differences" description="The selected version matches the current snapshot." />
                  ) : (
                    <div className="overflow-hidden rounded-2xl border border-border/60">
                      <table className="w-full text-sm">
                        <thead className="bg-muted/40">
                          <tr>
                            <th className="px-4 py-3 text-left font-semibold">Field</th>
                            <th className="px-4 py-3 text-left font-semibold">Previous</th>
                            <th className="px-4 py-3 text-left font-semibold">Current</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                          {diff.map((item, index) => (
                            <tr key={`${item.field}-${index}`} className="hover:bg-muted/20">
                              <td className="px-4 py-3 font-medium">{item.field.replace(/_/g, ' ')}</td>
                              <td className="px-4 py-3 text-muted-foreground line-through">{item.oldValue ?? '—'}</td>
                              <td className="px-4 py-3 text-foreground">{item.newValue ?? '—'}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </CardContent>
              </Card>
            </TabsContent>
          </Tabs>

          {canAct && actionable && (
            isFetching ? (
              <DeclarationActionCardSkeleton />
            ) : (
            <Card className="border-border/60 bg-card/95 shadow-soft-md">
              <CardHeader>
                <CardTitle className="text-base">Review outcomes</CardTitle>
                <CardDescription>Approve, reject, or request clarification from the temple authority.</CardDescription>
              </CardHeader>
              <CardContent className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                <Button onClick={() => openDialog('approve', declaration.id)} className="justify-start" disabled={isSubmitting}>
                  <BadgeCheck size={16} className="mr-2" />
                  Approve
                </Button>
                <Button variant="destructive" onClick={() => openDialog('reject', declaration.id)} className="justify-start" disabled={isSubmitting}>
                  <XCircle size={16} className="mr-2" />
                  Reject
                </Button>
                <Button variant="outline" onClick={() => openDialog('clarify', declaration.id)} className="justify-start" disabled={isSubmitting}>
                  <HelpCircle size={16} className="mr-2" />
                  Request clarification
                </Button>
                <Button variant="outline" onClick={() => openDialog('schedule-site-visit', declaration.id)} className="justify-start" disabled={isSubmitting}>
                  <ShieldAlert size={16} className="mr-2" />
                  Schedule site visit
                </Button>
              </CardContent>
            </Card>
            )
          )}
        </div>

        <aside className="space-y-4 xl:sticky xl:top-6 xl:self-start">
          <Card className="border-border/60 bg-card/95 shadow-soft-md xl:sticky xl:top-6">
            <CardHeader>
              <CardTitle className="text-base">Action rail</CardTitle>
              <CardDescription>Sticky controls for the review workflow.</CardDescription>
            </CardHeader>
            <CardContent className="space-y-3">
              {canAct && actionable ? (
                <>
                  <Button className="w-full justify-start" onClick={() => openDialog('approve', declaration.id)} disabled={isSubmitting}>
                    <BadgeCheck size={16} className="mr-2" />
                    Approve
                  </Button>
                  <Button variant="destructive" className="w-full justify-start" onClick={() => openDialog('reject', declaration.id)} disabled={isSubmitting}>
                    <XCircle size={16} className="mr-2" />
                    Reject
                  </Button>
                  <Button variant="outline" className="w-full justify-start" onClick={() => openDialog('clarify', declaration.id)} disabled={isSubmitting}>
                    <HelpCircle size={16} className="mr-2" />
                    Clarify
                  </Button>
                  <Button variant="outline" className="w-full justify-start" onClick={() => openDialog('schedule-site-visit', declaration.id)} disabled={isSubmitting}>
                    <Stamp size={16} className="mr-2" />
                    Schedule site visit
                  </Button>
                </>
              ) : (
                <div className="rounded-2xl border border-dashed border-border/70 bg-muted/20 p-4 text-sm text-muted-foreground">
                  This declaration is not currently actionable.
                </div>
              )}
            </CardContent>
          </Card>

          <Card className="border-border/60 bg-card/95 shadow-soft-md">
            <CardHeader>
              <CardTitle className="text-base">DC summary</CardTitle>
              <CardDescription>Important status markers for this filing.</CardDescription>
            </CardHeader>
            <CardContent className="space-y-3 text-sm">
              <DetailRow label="Temple" value={`#${declaration.templeId}`} />
              <DetailRow label="Due date" value={formatDate(declaration.dueDate)} />
              <DetailRow label="Submitted" value={formatDate(declaration.submittedAt)} />
              <DetailRow label="Reviewed" value={formatDate(declaration.reviewedAt)} />
              <DetailRow label="Remarks" value={declaration.remarks ?? 'None'} />
            </CardContent>
          </Card>
        </aside>
      </div>

      <WorkflowDialogs
        dialog={dialog}
        onClose={closeDialog}
        onApprove={confirmApprove}
        onReject={confirmReject}
        onClarify={confirmClarify}
        onScheduleSiteVisit={confirmScheduleSiteVisit}
        isSubmitting={isSubmitting}
      />
    </div>
  )
}

function WorkflowDialogs({
  dialog,
  onClose,
  onApprove,
  onReject,
  onClarify,
  onScheduleSiteVisit,
  isSubmitting,
}: {
  dialog: ReturnType<typeof useWorkflowActions>['dialog']
  onClose: () => void
  onApprove: (body: any) => Promise<void>
  onReject: (body: any) => Promise<void>
  onClarify: (body: any) => Promise<void>
  onScheduleSiteVisit: (body: any) => Promise<void>
  isSubmitting: boolean
}) {
  return (
    <>
      <WorkflowDialog open={dialog.open && dialog.kind === 'approve'} kind="approve" onClose={onClose} onSubmit={onApprove} isSubmitting={isSubmitting} />
      <WorkflowDialog open={dialog.open && dialog.kind === 'reject'} kind="reject" onClose={onClose} onSubmit={onReject} isSubmitting={isSubmitting} />
      <WorkflowDialog open={dialog.open && dialog.kind === 'clarify'} kind="clarify" onClose={onClose} onSubmit={onClarify} isSubmitting={isSubmitting} />
      <WorkflowDialog open={dialog.open && dialog.kind === 'schedule-site-visit'} kind="schedule-site-visit" onClose={onClose} onSubmit={onScheduleSiteVisit} isSubmitting={isSubmitting} />
    </>
  )
}

type DialogKind = 'approve' | 'reject' | 'clarify' | 'schedule-site-visit'

const DIALOG_META: Record<DialogKind, {
  title: string
  description: string
  field: 'notes' | 'reason' | 'message'
  label: string
  placeholder: string
  schema: typeof workflowApproveSchema | typeof workflowRejectSchema | typeof dcClarifySchema
}> = {
  approve: {
    title: 'Approve Declaration',
    description: 'Approve the declaration and generate the digital acknowledgement.',
    field: 'notes',
    label: 'Approval notes',
    placeholder: 'Optional internal notes...',
    schema: workflowApproveSchema,
  },
  reject: {
    title: 'Reject Declaration',
    description: 'Rejection returns the filing to the temple authority.',
    field: 'reason',
    label: 'Rejection reason',
    placeholder: 'Explain the reason for rejection...',
    schema: workflowRejectSchema,
  },
  clarify: {
    title: 'Request Clarification',
    description: 'The temple authority will be asked to correct or explain the filing.',
    field: 'message',
    label: 'Clarification message',
    placeholder: 'Describe the correction or clarification required...',
    schema: dcClarifySchema,
  },
  'schedule-site-visit': {
    title: 'Schedule Site Visit',
    description: 'Schedule a physical site visit for this declaration.',
    field: 'notes',
    label: 'Site visit notes',
    placeholder: 'Explain why a site visit is required...',
    schema: workflowApproveSchema,
  },
}

function WorkflowDialog({
  open,
  kind,
  onClose,
  onSubmit,
  isSubmitting,
}: {
  open: boolean
  kind: DialogKind
  onClose: () => void
  onSubmit: (body: any) => Promise<void>
  isSubmitting: boolean
}) {
  const meta = DIALOG_META[kind]
  const form = useForm<{ notes?: string; reason?: string; message?: string }>({
    resolver: zodResolver(meta.schema),
    defaultValues: { [meta.field]: '' } as any,
  })

  useEffect(() => {
    if (open) {
      form.reset({ [meta.field]: '' } as any)
    }
  }, [open, form, meta.field])

  const submit = form.handleSubmit(async (values) => {
    await onSubmit(values)
    onClose()
  })

  return (
    <AlertDialog open={open} onOpenChange={(next) => !next && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{meta.title}</AlertDialogTitle>
          <AlertDialogDescription>{meta.description}</AlertDialogDescription>
        </AlertDialogHeader>
        <Form {...form}>
          <form onSubmit={submit} className="space-y-4">
            <FormField
              control={form.control}
              name={meta.field}
              render={({ field }) => (
                <FormItem>
                  <FormLabel>{meta.label}</FormLabel>
                  <FormControl>
                    <Textarea {...field} placeholder={meta.placeholder} rows={4} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <AlertDialogFooter>
              <AlertDialogCancel type="button" onClick={onClose}>Cancel</AlertDialogCancel>
              <AlertDialogAction asChild>
                <Button type="submit" disabled={isSubmitting}>
                  {isSubmitting ? 'Submitting...' : 'Confirm'}
                </Button>
              </AlertDialogAction>
            </AlertDialogFooter>
          </form>
        </Form>
      </AlertDialogContent>
    </AlertDialog>
  )
}

function ClarificationCard({ item }: { item: ClarificationItemResponse }) {
  const direction = item.direction === 'DC_TO_TEMPLE' ? 'DC → Temple authority' : 'Temple authority → DC'
  return (
    <div className={cn(
      'rounded-2xl border p-4',
      item.direction === 'DC_TO_TEMPLE' ? 'border-primary/20 bg-primary/5' : 'border-border/60 bg-muted/20',
    )}>
      <div className="flex items-center justify-between gap-3">
        <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{direction}</p>
        <p className="text-xs text-muted-foreground">{formatDate(item.createdAt)}</p>
      </div>
      <p className="mt-2 text-sm text-foreground whitespace-pre-wrap">{item.message ?? item.notes}</p>
    </div>
  )
}

function VersionCard({
  version,
  selected,
  onSelect,
}: {
  version: DeclarationVersionResponse
  selected: boolean
  onSelect: () => void
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={cn(
        'w-full rounded-2xl border p-4 text-left transition-all duration-200',
        selected ? 'border-primary/30 bg-primary/5 shadow-soft-md' : 'border-border/60 bg-background/80',
      )}
    >
      <div className="flex flex-col gap-2 lg:flex-row lg:items-center lg:justify-between">
        <div className="space-y-1">
          <div className="flex items-center gap-2">
            <p className="text-sm font-semibold text-foreground">Version {version.versionNumber}</p>
            <StatusBadge status={version.status ?? 'DRAFT'} />
          </div>
          <p className="text-xs text-muted-foreground">
            Submitted {formatDate(version.submittedAt)} · Reviewed {formatDate(version.reviewedAt)}
          </p>
        </div>
        <div className="text-xs text-muted-foreground">
          {version.acknowledgementNumber ? `Ack ${version.acknowledgementNumber}` : 'Awaiting acknowledgement'}
        </div>
      </div>
      {version.remarks && <p className="mt-3 text-sm text-muted-foreground">{version.remarks}</p>}
    </button>
  )
}

function SelectVersion({
  versions,
  value,
  onChange,
}: {
  versions: DeclarationVersionResponse[]
  value?: number
  onChange: (value: number | undefined) => void
}) {
  return (
    <Select value={value ? String(value) : 'current'} onValueChange={(next) => onChange(next === 'current' ? undefined : Number(next))}>
      <SelectTrigger className="min-w-[220px]">
        <SelectValue placeholder="Compare version" />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="current">Current version</SelectItem>
        {versions.map((version) => (
          <SelectItem key={version.id} value={String(version.versionNumber)}>
            Version {version.versionNumber}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}

function AssetGroup({
  title,
  icon,
  children,
}: {
  title: string
  icon: React.ReactNode
  children: React.ReactNode
}) {
  return (
    <Card className="border-border/60 bg-card/95 shadow-soft-md">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <span className="rounded-xl bg-primary/10 p-2 text-primary">{icon}</span>
          {title}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">{children}</CardContent>
    </Card>
  )
}

function AssetSection<T>({
  title,
  items,
  renderItem,
}: {
  title: string
  items: T[] | null | undefined
  renderItem: (item: T) => React.ReactNode
}) {
  const safeItems = items ?? []
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-3">
        <h3 className="text-sm font-semibold text-foreground">{title}</h3>
        <span className="text-xs text-muted-foreground">{safeItems.length} item(s)</span>
      </div>
      {safeItems.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-border/70 bg-muted/20 p-4 text-sm text-muted-foreground">No entries recorded.</div>
      ) : (
        <div className="space-y-3">
          {safeItems.map((item, index) => (
            <div key={index} className="rounded-2xl border border-border/60 bg-background/80 p-4">
              {renderItem(item)}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function AssetLine({ title, subtitle, value }: { title: string; subtitle: string; value: string }) {
  return (
    <div className="flex flex-col gap-1 sm:flex-row sm:items-start sm:justify-between sm:gap-4">
      <div>
        <p className="text-sm font-semibold text-foreground">{title}</p>
        <p className="text-xs text-muted-foreground">{subtitle}</p>
      </div>
      <p className="text-xs font-medium text-muted-foreground">{value}</p>
    </div>
  )
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-3 rounded-xl border border-border/60 bg-muted/20 px-4 py-3">
      <span className="text-xs uppercase tracking-wide text-muted-foreground">{label}</span>
      <span className="text-sm font-medium text-foreground">{value}</span>
    </div>
  )
}

function MetricBox({
  label,
  value,
  unit,
  money,
}: {
  label: string
  value: number | null | undefined
  unit?: string
  money?: boolean
}) {
  return (
    <div className="rounded-2xl border border-border/60 bg-muted/20 p-4">
      <p className="text-xs uppercase tracking-wide text-muted-foreground">{label}</p>
      <p className="mt-2 text-lg font-semibold text-foreground">
        {money ? formatCurrency(value ?? 0) : `${(value ?? 0).toLocaleString()}${unit ? ` ${unit}` : ''}`}
      </p>
    </div>
  )
}

function MiniStat({ label, value }: { label: string; value: number | string }) {
  return (
    <div className="rounded-2xl border border-border/60 bg-background/80 p-4 shadow-soft-sm">
      <div className="text-[11px] uppercase tracking-wide text-muted-foreground">{label}</div>
      <div className="mt-2 text-lg font-semibold text-foreground">{value}</div>
    </div>
  )
}

function DeclarationSkeleton() {
  return (
    <div className="space-y-4 animate-pulse">
      <div className="h-44 rounded-3xl bg-muted" />
      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_360px]">
        <div className="space-y-4">
          <div className="h-12 rounded-2xl bg-muted" />
          <div className="h-72 rounded-2xl bg-muted" />
          <div className="h-72 rounded-2xl bg-muted" />
        </div>
        <div className="h-[640px] rounded-2xl bg-muted" />
      </div>
    </div>
  )
}

function formatDate(value?: string | null) {
  if (!value) return '—'
  return new Date(value).toLocaleDateString('en-IN')
}

function formatCurrency(value: number) {
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(value)
}
