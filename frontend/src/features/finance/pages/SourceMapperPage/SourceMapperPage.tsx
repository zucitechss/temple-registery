import { useMemo, useState } from 'react'
import { AlertCircle, Database, Info, ListTree, Pencil, Plus, RefreshCw, Search } from 'lucide-react'
import { toast } from 'sonner'
import { useAppSelector } from '@/app/store'
import { useGetCurrentUserQuery } from '@/features/auth/authApi'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { DataTable, type ColumnDef } from '@/components/data-display/DataTable/DataTable'
import { KpiCard } from '@/components/data-display/KpiCard/KpiCard'
import { StatusBadge } from '@/components/data-display/StatusBadge/StatusBadge'
import { EmptyState } from '@/components/feedback/EmptyState/EmptyState'
import { ReadOnlyBanner } from '@/components/feedback/ReadOnlyBanner/ReadOnlyBanner'
import {
  useListMappingRulesQuery,
  useListSourceSystemsQuery,
  useListUnresolvedValuesQuery,
} from '../../financeApi'
import { parseApiError } from '../../financeErrors'
import { getMappingCapabilities } from '../../financePermissions'
import { NamespacedValue } from '../../components/NamespacedValue'
import { MappingRuleDrawer, type DrawerIntent } from '../../components/MappingRuleDrawer'
import { UnresolvedValuesPanel } from '../../components/UnresolvedValuesPanel'
import type { MappingOutcome, MappingRule, SortableKey } from '../../financeTypes'

const PAGE_SIZE = 20

type StatusFilter = 'all' | 'active' | 'inactive'

/**
 * The Source Mapper (FIN-054B).
 *
 * <p>Semantic mapping only: what a source *value* means. Which tables and columns a connector
 * reads is code, by architectural decision (ADR-004), and this screen deliberately offers no way
 * to configure it — presenting one would promise something the platform does not do.
 *
 * <p>Every metric shown is a server-side total or an explicitly batch-scoped count. Nothing is
 * derived from the current page of results, because a total computed from twenty rows out of two
 * hundred is a wrong number presented confidently.
 */
export function SourceMapperPage() {
  const currentUser = useAppSelector((s) => s.auth.currentUser)
  const { data: meData } = useGetCurrentUserQuery()
  const role = currentUser?.role ?? meData?.data?.role
  const can = getMappingCapabilities(role)

  const [sourceSystemId, setSourceSystemId] = useState<number | null>(null)
  const [page, setPage] = useState(0)
  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all')
  const [sortKey, setSortKey] = useState<SortableKey>('priority')
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('desc')
  const [outcome, setOutcome] = useState<MappingOutcome>('UNMAPPED')
  const [drawerIntent, setDrawerIntent] = useState<DrawerIntent | null>(null)

  const {
    data: sourceData,
    isLoading: loadingSources,
    isError: sourcesFailed,
    error: sourcesError,
    refetch: refetchSources,
  } = useListSourceSystemsQuery()

  const sourceSystems = useMemo(() => sourceData?.data ?? [], [sourceData])
  const selectedSourceId = sourceSystemId ?? sourceSystems[0]?.id ?? null
  const selectedSource = sourceSystems.find((s) => s.id === selectedSourceId) ?? null

  const activeFilter = statusFilter === 'all' ? undefined : statusFilter === 'active'

  const {
    data: rulesData,
    isFetching: fetchingRules,
    isError: rulesFailed,
    error: rulesError,
    refetch: refetchRules,
  } = useListMappingRulesQuery(
    {
      sourceSystemId: selectedSourceId!,
      active: activeFilter,
      q: search.trim() || undefined,
      page,
      size: PAGE_SIZE,
      sort: `${sortKey},${sortOrder}`,
    },
    { skip: selectedSourceId == null },
  )

  // A separate one-row query purely for the total. The alternative — counting the rows on screen —
  // would report "20 inactive rules" for any source that has more than a page of them.
  const { data: inactiveCountData } = useListMappingRulesQuery(
    { sourceSystemId: selectedSourceId!, active: false, page: 0, size: 1 },
    { skip: selectedSourceId == null },
  )

  const { data: unmappedData } = useListUnresolvedValuesQuery(
    { sourceSystemId: selectedSourceId!, outcome: 'UNMAPPED' },
    { skip: selectedSourceId == null },
  )
  const { data: ambiguousData } = useListUnresolvedValuesQuery(
    { sourceSystemId: selectedSourceId!, outcome: 'AMBIGUOUS' },
    { skip: selectedSourceId == null },
  )

  const rules = rulesData?.data?.content ?? []
  const totalPages = rulesData?.data?.totalPages ?? 0
  const totalElements = rulesData?.data?.totalElements ?? 0

  const unmappedBatch = unmappedData?.data?.syncBatchId ?? null
  const unmappedCount = unmappedData?.data?.values.length ?? 0
  const ambiguousCount = ambiguousData?.data?.values.length ?? 0

  const onSortChange = (key: string) => {
    if (key === sortKey) {
      setSortOrder((o) => (o === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortKey(key as SortableKey)
      setSortOrder('asc')
    }
    setPage(0)
  }

  const openCreateFromValue = (namespace: string, sourceValue: string) => {
    if (!can.canCreate) {
      toast.error('You do not have permission to create mapping rules.')
      return
    }
    // The observed value is carried through untouched — no trimming, no case change. The server
    // matches exactly, so "tidying" it here would create a rule for a different value.
    setDrawerIntent({ mode: 'create', namespace, sourceValue })
  }

  const columns: ColumnDef<MappingRule>[] = [
    {
      key: 'sourceValue',
      header: 'Source value',
      sortable: true,
      cell: (row) => (
        <div className="space-y-1">
          <NamespacedValue
            namespace={row.namespace}
            sourceValue={row.sourceValue}
            storedValue={row.storedValue}
            wellFormed={row.wellFormed}
          />
          {row.sourceLabel && (
            <p className="text-xs text-muted-foreground line-clamp-1">{row.sourceLabel}</p>
          )}
          {!row.wellFormed && (
            <p className="text-xs text-destructive">
              Names no staged field — this rule can never match a record.
            </p>
          )}
        </div>
      ),
    },
    {
      key: 'canonicalValue',
      header: 'Revenue category',
      sortable: true,
      cell: (row) => (
        <div className="space-y-1">
          <span className="font-medium text-foreground">{row.canonicalValue}</span>
          {!row.canonicalValueKnown && (
            <p className="text-xs text-destructive">Not an active category.</p>
          )}
        </div>
      ),
    },
    {
      key: 'mappingType',
      header: 'Type',
      sortable: true,
      className: 'hidden lg:table-cell',
      cell: (row) => (
        <span className="text-xs text-muted-foreground">{row.mappingType.replace(/_/g, ' ')}</span>
      ),
    },
    {
      key: 'priority',
      header: 'Priority',
      sortable: true,
      className: 'hidden sm:table-cell',
      cell: (row) => <span className="tabular-nums">{row.priority}</span>,
    },
    {
      key: 'active',
      header: 'Status',
      sortable: true,
      cell: (row) => <StatusBadge status={row.active ? 'ACTIVE' : 'RETIRED'} />,
    },
    {
      key: 'updatedAt',
      header: 'Updated',
      sortable: true,
      className: 'hidden xl:table-cell',
      cell: (row) => (
        <div className="text-xs text-muted-foreground">
          <div>{new Date(row.updatedAt).toLocaleDateString()}</div>
          {row.updatedBy ? <div>by user {row.updatedBy}</div> : null}
        </div>
      ),
    },
    {
      key: 'actions',
      header: '',
      className: 'text-right',
      cell: (row) =>
        can.canEdit ? (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setDrawerIntent({ mode: 'edit', rule: row })}
            aria-label={`Edit mapping for ${row.storedValue}`}
          >
            <Pencil size={14} className="mr-1" aria-hidden />
            Edit
          </Button>
        ) : null,
    },
  ]

  return (
    <div className="space-y-6">
      {/* ── Header ─────────────────────────────────────────────────────────── */}
      <header className="space-y-2">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="font-display text-2xl font-bold tracking-tight text-foreground">
              Source Mapper
            </h1>
            <p className="mt-1 max-w-3xl text-sm text-muted-foreground">
              Decide what a source system&rsquo;s values mean. A mapping rule translates one raw
              value — a seva code, a counter code — into a canonical revenue category, so income
              from different temples can be counted the same way.
            </p>
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              refetchSources()
              if (selectedSourceId != null) refetchRules()
            }}
            disabled={loadingSources || fetchingRules}
          >
            <RefreshCw
              size={14}
              className={loadingSources || fetchingRules ? 'mr-2 animate-spin' : 'mr-2'}
              aria-hidden
            />
            Refresh
          </Button>
        </div>
      </header>

      {!can.canCreate && can.canView && (
        <ReadOnlyBanner
          roleLabel={`${role ?? 'Read only'}`}
          message="You can review mapping rules and unresolved values here. Changing a mapping is restricted to District Collectors and Super Admins."
        />
      )}

      {/* ── The sentence this whole screen has to be honest about ───────────── */}
      <Alert className="border-info/40 bg-info/5">
        <Info size={16} className="text-info" aria-hidden />
        <AlertTitle className="text-sm">Mapping changes apply to future pipeline runs</AlertTitle>
        <AlertDescription className="text-xs leading-relaxed text-muted-foreground">
          Saving a mapping does not automatically correct previously processed financial figures.
          Records already loaded keep the classification they were loaded with until the batch that
          produced them is processed again, which is a separate approved process. Nothing on this
          screen edits or re-runs historical financial data.
        </AlertDescription>
      </Alert>

      {/* ── Source system selector ──────────────────────────────────────────── */}
      {sourcesFailed && (
        <Alert variant="destructive">
          <AlertCircle size={16} aria-hidden />
          <AlertDescription className="flex flex-wrap items-center justify-between gap-3">
            <span className="text-sm">{parseApiError(sourcesError).message}</span>
            <Button variant="outline" size="sm" onClick={() => refetchSources()}>
              Try again
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {!sourcesFailed && !loadingSources && sourceSystems.length === 0 && (
        <EmptyState
          title="No source systems available"
          description="No finance source system is registered for a temple in your jurisdiction, so there is nothing to map yet."
          icon={<Database size={28} aria-hidden />}
        />
      )}

      {sourceSystems.length > 0 && selectedSourceId != null && (
        <>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
            <div className="flex-1 space-y-1.5 sm:max-w-md">
              <label htmlFor="source-system" className="text-sm font-medium text-foreground">
                Source system
              </label>
              <Select
                value={String(selectedSourceId)}
                onValueChange={(v) => {
                  setSourceSystemId(Number(v))
                  setPage(0)
                }}
              >
                <SelectTrigger id="source-system">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {sourceSystems.map((source) => (
                    <SelectItem key={source.id} value={String(source.id)}>
                      {source.templeName} — {source.systemName}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            {selectedSource && (
              <p className="text-xs text-muted-foreground sm:pb-2">
                Code <code className="font-mono">{selectedSource.systemCode}</code> · temple{' '}
                {selectedSource.templeId}
              </p>
            )}
          </div>

          {/* ── Metrics. Server-side totals or explicitly batch-scoped. ──────── */}
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <KpiCard
              title="Active rules"
              value={selectedSource?.activeRuleCount ?? 0}
              icon={<ListTree size={18} aria-hidden />}
              description="Revenue category rules the pipeline will read"
            />
            <KpiCard
              title="Inactive rules"
              value={inactiveCountData?.data?.totalElements ?? 0}
              description="Kept for the record, ignored by the pipeline"
            />
            <KpiCard
              title="Unmapped values"
              value={unmappedCount}
              description={
                unmappedBatch == null
                  ? 'Nothing mapped yet — not measured'
                  : `Distinct values in batch ${unmappedBatch}`
              }
            />
            <KpiCard
              title="Ambiguous values"
              value={ambiguousCount}
              description={
                unmappedBatch == null
                  ? 'Nothing mapped yet — not measured'
                  : 'Rules tied on priority in the latest batch'
              }
            />
          </div>

          {/* ── Tabs ─────────────────────────────────────────────────────────── */}
          <Tabs defaultValue="rules" className="space-y-4">
            <TabsList>
              <TabsTrigger value="rules">Mapping rules</TabsTrigger>
              <TabsTrigger value="unresolved">
                Needs attention
                {unmappedCount > 0 && (
                  <span className="ml-2 rounded-full bg-warning/15 px-1.5 py-0.5 text-xs font-semibold text-warning">
                    {unmappedCount}
                  </span>
                )}
              </TabsTrigger>
            </TabsList>

            <TabsContent value="rules" className="space-y-4">
              <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <div className="flex flex-1 flex-col gap-3 sm:flex-row sm:items-center">
                  <div className="relative flex-1 sm:max-w-xs">
                    <Search
                      size={15}
                      className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
                      aria-hidden
                    />
                    <Input
                      value={search}
                      onChange={(e) => {
                        setSearch(e.target.value)
                        setPage(0)
                      }}
                      placeholder="Search value, label or category"
                      className="pl-9"
                      aria-label="Search mapping rules"
                    />
                  </div>
                  <Select
                    value={statusFilter}
                    onValueChange={(v) => {
                      setStatusFilter(v as StatusFilter)
                      setPage(0)
                    }}
                  >
                    <SelectTrigger className="w-full sm:w-40" aria-label="Filter by status">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="all">All statuses</SelectItem>
                      <SelectItem value="active">Active only</SelectItem>
                      <SelectItem value="inactive">Inactive only</SelectItem>
                    </SelectContent>
                  </Select>
                </div>

                {can.canCreate && (
                  <Button onClick={() => setDrawerIntent({ mode: 'create' })}>
                    <Plus size={15} className="mr-1.5" aria-hidden />
                    New mapping
                  </Button>
                )}
              </div>

              {rulesFailed ? (
                <Alert variant="destructive">
                  <AlertCircle size={16} aria-hidden />
                  <AlertDescription className="flex flex-wrap items-center justify-between gap-3">
                    <span className="text-sm">{parseApiError(rulesError).message}</span>
                    <Button variant="outline" size="sm" onClick={() => refetchRules()}>
                      Try again
                    </Button>
                  </AlertDescription>
                </Alert>
              ) : (
                <>
                  <DataTable
                    columns={columns}
                    data={rules}
                    isLoading={fetchingRules && !rulesData}
                    rowKey={(row) => row.id}
                    emptyTitle="No mapping rules match"
                    emptyDescription="Adjust the search or status filter, or create a rule from an unresolved value."
                    sortKey={sortKey}
                    sortOrder={sortOrder}
                    onSortChange={onSortChange}
                    pagination={{ page, totalPages, onPageChange: setPage }}
                  />
                  {totalElements > 0 && (
                    <p className="text-xs text-muted-foreground">
                      {totalElements.toLocaleString()} rule{totalElements === 1 ? '' : 's'} match
                      the current filter.
                    </p>
                  )}
                </>
              )}
            </TabsContent>

            <TabsContent value="unresolved">
              <UnresolvedValuesPanel
                sourceSystemId={selectedSourceId}
                outcome={outcome}
                onOutcomeChange={setOutcome}
                canCreate={can.canCreate}
                onCreateFromValue={openCreateFromValue}
              />
            </TabsContent>
          </Tabs>

          <MappingRuleDrawer
            open={drawerIntent !== null}
            onOpenChange={(next) => !next && setDrawerIntent(null)}
            intent={drawerIntent}
            sourceSystemId={selectedSourceId}
          />
        </>
      )}
    </div>
  )
}
