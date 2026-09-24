import { useMemo, useState } from 'react'
import {
  ResponsiveContainer,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip as RechartsTooltip,
  CartesianGrid,
} from 'recharts'
import { AlertCircle, CalendarClock, IndianRupee, Receipt, ShieldCheck, TrendingUp, Wallet } from 'lucide-react'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { CardSkeleton } from '@/components/feedback/Skeleton/Skeleton'
import { EmptyState } from '@/components/feedback/EmptyState/EmptyState'
import { cn } from '@/lib/utils'
import {
  useGetCategoryBreakdownQuery,
  useGetFinanceCapabilitiesQuery,
  useGetFinanceSummaryQuery,
  useGetMonthlyRevenueQuery,
  useGetReconciliationQuery,
  useGetRevenueTrendQuery,
} from '../../financeReportApi'
import { parseReportError } from '../../financeReportErrors'
import { formatRupeesCompact, formatRupeesFull } from '../../financeReportFormat'
import { currentFinancialYear } from '../../financeYear'
import { AvailabilityNotice } from '../../components/AvailabilityNotice'
import { MetricCard } from '../../components/MetricCard'
import type { DataAvailability, ReconciliationCheckType } from '../../financeReportTypes'

interface FinanceDashboardTabProps {
  templeId: number
}

const CHECK_TYPE_LABEL: Record<ReconciliationCheckType, { label: string; authoritative: boolean }> = {
  STAGE_COMPLETENESS: { label: 'Every staged record reached a final state', authoritative: true },
  REJECTION_ACCOUNTING: { label: 'Every rejected record has a recorded reason', authoritative: true },
  SOURCE_VS_CENTRAL: { label: "Matches the source system's own total", authoritative: false },
  SUSPECTED_SOURCE_DELETION: { label: 'No records disappeared from a closed period', authoritative: false },
}

const AVAILABILITY_BADGE: Record<DataAvailability, string> = {
  AVAILABLE: 'bg-success/10 text-success border-success/20',
  PARTIALLY_AVAILABLE: 'bg-warning/10 text-warning border-warning/20',
  NOT_AVAILABLE: 'bg-muted text-muted-foreground border-border',
  NOT_APPLICABLE: 'bg-muted text-muted-foreground border-border',
}

/**
 * The temple finance dashboard (FIN-091), reading exclusively from the reporting API FIN-081/082/083
 * built — never a hard-coded figure, and never limited to one temple.
 *
 * <p>Every section renders exactly what its query returns. A metric this platform cannot yet
 * answer shows {@link AvailabilityNotice} with the backend's own reason; it is never estimated,
 * assumed, or silently omitted. There is no year picker beyond the buttons drawn from
 * `revenue/trend`'s own years — a control offering a year the backend has never published data for
 * would be a promise this dashboard cannot keep.
 */
export function FinanceDashboardTab({ templeId }: FinanceDashboardTabProps) {
  const [selectedYear, setSelectedYear] = useState<string | null>(null)

  const capabilitiesQuery = useGetFinanceCapabilitiesQuery(templeId)
  const trendQuery = useGetRevenueTrendQuery(templeId)

  const availableYears = useMemo(
    () => trendQuery.data?.data?.years.map((y) => y.financialYear) ?? [],
    [trendQuery.data],
  )
  const defaultYear = useMemo(() => {
    const preferred = currentFinancialYear()
    if (availableYears.includes(preferred)) return preferred
    return availableYears[availableYears.length - 1] ?? preferred
  }, [availableYears])
  const financialYear = selectedYear ?? defaultYear

  const summaryQuery = useGetFinanceSummaryQuery({ templeId, financialYear })
  const monthlyQuery = useGetMonthlyRevenueQuery({ templeId, financialYear })
  const categoriesQuery = useGetCategoryBreakdownQuery({ templeId, financialYear })
  const reconciliationQuery = useGetReconciliationQuery({ templeId, financialYear })

  if (capabilitiesQuery.isLoading) {
    return (
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4 p-1">
        {Array.from({ length: 4 }).map((_, i) => <CardSkeleton key={i} />)}
      </div>
    )
  }

  if (capabilitiesQuery.isError) {
    const err = parseReportError(capabilitiesQuery.error)
    return (
      <Alert variant="destructive" className="m-1">
        <AlertCircle size={16} />
        <AlertTitle>Could not load finance figures</AlertTitle>
        <AlertDescription>{err.message}</AlertDescription>
      </Alert>
    )
  }

  const summary = summaryQuery.data?.data
  const freshness = summary?.dataFreshness

  return (
    <div className="p-1 space-y-5">
      {freshness && (
        <div
          className={cn(
            'rounded-xl border p-3 flex items-center gap-2.5 text-sm',
            freshness.status === 'STALE'
              ? 'border-warning/30 bg-warning/5 text-warning-foreground'
              : 'border-success/20 bg-success/5',
          )}
        >
          <CalendarClock size={16} className="shrink-0" />
          <span>
            {freshness.status === 'STALE'
              ? (freshness.stalenessReason ?? 'This temple’s finance data may be out of date.')
              : `Last synced ${new Date(freshness.lastSyncedAt!).toLocaleString('en-IN')}`}
            {freshness.sourceDataThrough && (
              <span className="text-muted-foreground"> · Source data through {freshness.sourceDataThrough}</span>
            )}
          </span>
        </div>
      )}

      {availableYears.length > 0 && (
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">Financial year</span>
          {availableYears.map((y) => (
            <Button
              key={y}
              size="sm"
              variant={y === financialYear ? 'default' : 'outline'}
              onClick={() => setSelectedYear(y)}
              className="h-7 px-2.5 text-xs"
            >
              {y}
            </Button>
          ))}
        </div>
      )}

      {/* Summary KPIs */}
      {summaryQuery.isLoading ? (
        <div className="grid grid-cols-1 md:grid-cols-4 gap-4"><CardSkeleton /><CardSkeleton /><CardSkeleton /><CardSkeleton /></div>
      ) : summaryQuery.isError ? (
        <SectionError error={summaryQuery.error} />
      ) : summary ? (
        <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
          <MetricCard title="Gross revenue" envelope={summary.grossRevenue} icon={<IndianRupee size={18} />} />
          <MetricCard title="Net revenue" envelope={summary.netRevenue} icon={<Wallet size={18} />} />
          <MetricCard title="Receipts recorded" envelope={summary.transactionCount} icon={<Receipt size={18} />} />
          <MetricCard title="Expenditure" envelope={summary.expenditure} icon={<TrendingUp size={18} />} />
        </div>
      ) : null}

      {/* Revenue trend */}
      <ChartSection title="Revenue by financial year" icon={<TrendingUp size={16} />}>
        {trendQuery.isLoading ? (
          <CardSkeleton />
        ) : trendQuery.isError ? (
          <SectionError error={trendQuery.error} />
        ) : (
          <RevenueBarChart
            data={(trendQuery.data?.data?.years ?? []).map((y) => ({
              label: y.financialYear,
              value: y.grossRevenue.availability === 'AVAILABLE' || y.grossRevenue.availability === 'PARTIALLY_AVAILABLE'
                ? y.grossRevenue.value
                : null,
            }))}
            emptyLabel="No financial year has published revenue yet."
          />
        )}
      </ChartSection>

      {/* Monthly revenue */}
      <ChartSection title={`Revenue by month, FY ${financialYear}`} icon={<CalendarClock size={16} />}>
        {monthlyQuery.isLoading ? (
          <CardSkeleton />
        ) : monthlyQuery.isError ? (
          <SectionError error={monthlyQuery.error} />
        ) : (
          <RevenueBarChart
            data={(monthlyQuery.data?.data?.months ?? []).map((m) => ({
              label: m.month.slice(5),
              value: m.grossRevenue.availability === 'AVAILABLE' || m.grossRevenue.availability === 'PARTIALLY_AVAILABLE'
                ? m.grossRevenue.value
                : null,
            }))}
            emptyLabel="No month in this financial year has published revenue yet."
          />
        )}
      </ChartSection>

      {/* Category breakdown */}
      <ChartSection title={`Revenue by category, FY ${financialYear}`} icon={<Wallet size={16} />}>
        {categoriesQuery.isLoading ? (
          <CardSkeleton />
        ) : categoriesQuery.isError ? (
          <SectionError error={categoriesQuery.error} />
        ) : (
          <RevenueBarChart
            data={(categoriesQuery.data?.data?.categories ?? []).map((c) => ({
              label: c.categoryName,
              value: c.grossRevenue.availability === 'AVAILABLE' || c.grossRevenue.availability === 'PARTIALLY_AVAILABLE'
                ? c.grossRevenue.value
                : null,
            }))}
            emptyLabel="No category has published revenue for this financial year yet."
            layout="horizontal"
          />
        )}
      </ChartSection>

      {/* Reconciliation */}
      <ChartSection title={`Verification checks, FY ${financialYear}`} icon={<ShieldCheck size={16} />}>
        {reconciliationQuery.isLoading ? (
          <CardSkeleton />
        ) : reconciliationQuery.isError ? (
          <SectionError error={reconciliationQuery.error} />
        ) : (reconciliationQuery.data?.data?.checks.length ?? 0) === 0 ? (
          <EmptyState title="No checks recorded" description="Nothing has been verified for this financial year yet." />
        ) : (
          <div className="space-y-2">
            {reconciliationQuery.data!.data!.checks.map((check, i) => {
              const meta = CHECK_TYPE_LABEL[check.checkType]
              return (
                <div key={i} className="flex items-center justify-between gap-3 rounded-lg border border-border p-3 text-sm">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="font-medium truncate">{meta?.label ?? check.checkType}</span>
                      <Badge variant="outline" className="text-[10px] px-1.5 py-0">
                        {meta?.authoritative ? 'Internal check' : 'Verified against source'}
                      </Badge>
                    </div>
                    {check.statusReason && (
                      <p className="text-xs text-muted-foreground mt-0.5">{check.statusReason}</p>
                    )}
                    {check.sourceTotal != null && check.centralTotal != null && (
                      <p className="text-xs text-muted-foreground mt-0.5">
                        Source: {formatRupeesFull(check.sourceTotal)} · Platform: {formatRupeesFull(check.centralTotal)}
                      </p>
                    )}
                  </div>
                  <Badge className={cn('shrink-0', AVAILABILITY_BADGE[
                    check.status === 'PASSED' ? 'AVAILABLE'
                      : check.status === 'NOT_AVAILABLE' ? 'NOT_AVAILABLE' : 'PARTIALLY_AVAILABLE'
                  ])}>
                    {check.status}
                  </Badge>
                </div>
              )
            })}
          </div>
        )}
      </ChartSection>

      {/* Capabilities */}
      <ChartSection title="What this temple's data can answer" icon={<ShieldCheck size={16} />}>
        {capabilitiesQuery.data?.data?.capabilities.length === 0 ? (
          <EmptyState title="No capabilities declared" description="This temple has not been onboarded to the finance platform yet." />
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2">
            {capabilitiesQuery.data?.data?.capabilities.map((cap) => (
              <div key={cap.capability} className="rounded-lg border border-border p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-xs font-semibold">{cap.capability.replace(/_/g, ' ')}</span>
                  <Badge className={cn('text-[10px] px-1.5 py-0', AVAILABILITY_BADGE[cap.availability])}>
                    {cap.availability.replace(/_/g, ' ')}
                  </Badge>
                </div>
                {cap.reason && <p className="text-xs text-muted-foreground mt-1">{cap.reason}</p>}
              </div>
            ))}
          </div>
        )}
      </ChartSection>
    </div>
  )
}

function SectionError({ error }: { error: unknown }) {
  const parsed = parseReportError(error)
  return (
    <Alert variant="destructive">
      <AlertCircle size={16} />
      <AlertDescription>{parsed.message}</AlertDescription>
    </Alert>
  )
}

function ChartSection({ title, icon, children }: { title: string; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="rounded-2xl border border-border bg-card/40 backdrop-blur-sm p-5 shadow-soft-sm">
      <p className="text-sm font-semibold mb-3 flex items-center gap-2">{icon}{title}</p>
      {children}
    </div>
  )
}

/**
 * A bar per point, with unavailable points simply absent from the chart rather than drawn as a
 * zero-height bar — a bar at height zero and "we were never told" must not look the same.
 */
function RevenueBarChart({
  data,
  emptyLabel,
  layout = 'vertical',
}: {
  data: { label: string; value: number | null }[]
  emptyLabel: string
  layout?: 'vertical' | 'horizontal'
}) {
  const known = data.filter((d) => d.value != null)
  const missingCount = data.length - known.length

  if (known.length === 0) {
    return <EmptyState title="No published figures" description={emptyLabel} />
  }

  const height = layout === 'horizontal' ? Math.max(180, known.length * 36) : 220

  return (
    <div>
      <div style={{ width: '100%', height }}>
        <ResponsiveContainer>
          <BarChart data={known} layout={layout === 'horizontal' ? 'vertical' : 'horizontal'} margin={{ left: layout === 'horizontal' ? 24 : 0 }}>
            <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
            {layout === 'horizontal' ? (
              <>
                <XAxis type="number" tickFormatter={(v) => formatRupeesCompact(v)} fontSize={11} />
                <YAxis type="category" dataKey="label" width={160} fontSize={11} />
              </>
            ) : (
              <>
                <XAxis dataKey="label" fontSize={11} />
                <YAxis tickFormatter={(v) => formatRupeesCompact(v)} fontSize={11} width={70} />
              </>
            )}
            <RechartsTooltip formatter={(v: number) => formatRupeesFull(v)} />
            <Bar dataKey="value" fill="hsl(var(--primary))" radius={4} maxBarSize={40} />
          </BarChart>
        </ResponsiveContainer>
      </div>
      {missingCount > 0 && (
        <p className="text-[11px] text-muted-foreground mt-2">
          {missingCount} of {data.length} not shown — no published figure is available for {missingCount === 1 ? 'it' : 'them'}.
        </p>
      )}
    </div>
  )
}
