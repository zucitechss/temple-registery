import { useMemo, useState } from 'react'
import { AlertCircle, Database, Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import { StatusBadge } from '@/components/data-display/StatusBadge/StatusBadge'
import { EmptyState } from '@/components/feedback/EmptyState/EmptyState'
import { TableSkeleton } from '@/components/feedback/Skeleton/Skeleton'
import {
  useGetSourceSystemQuery,
  useGetSourceSystemReadinessQuery,
  useListOnboardingSourceSystemsQuery,
} from '../../financeOnboardingApi'
import { parseOnboardingError } from '../../financeOnboardingErrors'
import { ReadinessPanel } from '../../components/ReadinessPanel'
import { RegisterSourceSystemDrawer } from '../../components/RegisterSourceSystemDrawer'
import { CapabilityMatrixPanel } from '../../components/CapabilityMatrixPanel'
import { SourceOfTruthPanel } from '../../components/SourceOfTruthPanel'
import { ActivationPanel } from '../../components/ActivationPanel'

/**
 * Finance source systems (FIN-140 slice 140-A).
 *
 * The smallest usable onboarding flow: register a temple's source system, and see what still
 * stands between it and being switched on.
 *
 * **The activate button says "Enable for sync", and the wording is the point.** It grants the
 * platform permission to contact the source in future. It starts no synchronisation, and today
 * nothing reads the flag at all — no connector has been deployed and no worker trigger exists. The
 * panel states that on both the enabled and the disabled verdict.
 *
 * **There is no "test connection" either.** The runtime serving these endpoints holds no connector
 * and no credential, so a connectivity check has nowhere to run. The readiness panel states that
 * rather than leaving a reader to infer that a clean verdict means a working integration.
 */
export function SourceSystemsPage() {
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [drawerOpen, setDrawerOpen] = useState(false)

  const {
    data: listData,
    isLoading: loadingList,
    isError: listFailed,
    error: listError,
  } = useListOnboardingSourceSystemsQuery()

  const sourceSystems = useMemo(() => listData?.data ?? [], [listData])
  const activeId = selectedId ?? sourceSystems[0]?.id ?? null

  const { data: detailData } = useGetSourceSystemQuery(activeId as number, {
    skip: activeId == null,
  })

  const {
    data: readinessData,
    isFetching: loadingReadiness,
    isError: readinessFailed,
    error: readinessError,
  } = useGetSourceSystemReadinessQuery(activeId as number, { skip: activeId == null })

  const detail = detailData?.data
  const readiness = readinessData?.data

  return (
    <div className="space-y-6 px-4 py-6 sm:px-6">
      <header className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Finance source systems</h1>
          <p className="mt-1 max-w-2xl text-sm text-muted-foreground">
            Which external system supplies each temple's financial data, and whether its
            configuration is complete enough to switch on.
          </p>
        </div>
        <Button onClick={() => setDrawerOpen(true)}>
          <Plus className="mr-2 h-4 w-4" aria-hidden />
          Register source system
        </Button>
      </header>

      {listFailed && (
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" aria-hidden />
          <AlertTitle>Source systems could not be loaded</AlertTitle>
          <AlertDescription>{parseOnboardingError(listError).message}</AlertDescription>
        </Alert>
      )}

      {loadingList && <TableSkeleton rows={4} />}

      {!loadingList && !listFailed && sourceSystems.length === 0 && (
        <EmptyState
          icon={<Database size={32} aria-hidden />}
          title="No source system is registered yet"
          description="Register one to record which external system supplies a temple's financial data. Nothing is contacted until it is configured and switched on."
        />
      )}

      {!loadingList && !listFailed && sourceSystems.length > 0 && (
        <div className="space-y-6">
          <div className="max-w-md space-y-2">
            <label htmlFor="source-system" className="text-sm font-medium">
              Source system
            </label>
            <Select
              value={activeId != null ? String(activeId) : undefined}
              onValueChange={(value) => setSelectedId(Number(value))}
            >
              <SelectTrigger id="source-system" aria-label="Source system">
                <SelectValue placeholder="Select a source system" />
              </SelectTrigger>
              <SelectContent>
                {sourceSystems.map((source) => (
                  <SelectItem key={source.id} value={String(source.id)}>
                    {source.templeName} — {source.systemCode}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {detail && <Configuration detail={detail} />}

          <section className="space-y-3">
            <h2 className="text-lg font-semibold tracking-tight">Capabilities</h2>
            <p className="text-sm text-muted-foreground">
              What this source system can tell the platform, and why when it cannot. Declaring a
              capability starts no traffic to the temple.
            </p>
            {activeId != null && <CapabilityMatrixPanel sourceSystemId={activeId} />}
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold tracking-tight">Source of truth</h2>
            <p className="text-sm text-muted-foreground">
              Which field in the source carries each figure, and why that one. Changing a
              declaration creates a new version and keeps the old, so a figure published under the
              previous one stays explicable.
            </p>
            {activeId != null && <SourceOfTruthPanel sourceSystemId={activeId} />}
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold tracking-tight">Activation</h2>
            <p className="text-sm text-muted-foreground">
              Whether the platform is permitted to contact this source system once the worker path
              exists. Enabling grants permission; it performs no connection and starts no
              synchronisation.
            </p>
            {activeId != null && detail && (
              <ActivationPanel
                sourceSystemId={activeId}
                enabledForSync={detail.syncEnabled}
                readiness={readiness}
              />
            )}
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold tracking-tight">Readiness</h2>
            <ReadinessPanel
              readiness={readiness}
              isLoading={loadingReadiness}
              isError={readinessFailed}
              errorMessage={readinessFailed ? parseOnboardingError(readinessError).message : undefined}
            />
          </section>
        </div>
      )}

      <RegisterSourceSystemDrawer
        open={drawerOpen}
        onOpenChange={setDrawerOpen}
        onRegistered={setSelectedId}
      />
    </div>
  )
}

function Configuration({
  detail,
}: {
  detail: NonNullable<ReturnType<typeof useGetSourceSystemQuery>['data']>['data']
}) {
  if (!detail) return null

  return (
    <section className="space-y-3">
      <div className="flex flex-wrap items-center gap-3">
        <h2 className="text-lg font-semibold tracking-tight">{detail.systemName}</h2>
        <StatusBadge status={detail.syncEnabled ? 'ACTIVE' : 'INACTIVE'} />
      </div>

      <dl className="grid gap-x-8 gap-y-3 sm:grid-cols-2 lg:grid-cols-3">
        <Field label="Temple" value={detail.templeName ?? `#${detail.templeId}`} />
        <Field label="System code" value={detail.systemCode} mono />
        <Field label="Technology" value={detail.sourceTechnology.replace(/_/g, ' ')} />
        <Field label="Connector type" value={detail.connectorType.replace(/_/g, ' ')} />
        <Field label="Connector bean" value={detail.connectorBean} mono />
        <Field label="Temple code in source" value={detail.sourceTempleCode ?? 'Not set'} mono />
        <Field label="Source database" value={detail.sourceDatabaseName ?? 'Not recorded'} mono />
        {/* Whether an alias exists, never which one. The response carries no field for the value. */}
        <Field
          label="Credential alias"
          value={detail.credentialRefSet ? 'Configured' : 'Not configured'}
        />
        <Field label="Time zone" value={detail.sourceTimezone ?? 'Asia/Kolkata'} />
      </dl>
    </section>
  )
}

function Field({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div>
      <dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</dt>
      <dd className={`mt-0.5 text-sm ${mono ? 'font-mono' : ''}`}>{value}</dd>
    </div>
  )
}
