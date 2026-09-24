import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { AvailabilityNotice } from './AvailabilityNotice'
import { formatReceiptCount, formatRupeesCompact } from '../financeReportFormat'
import type { MetricEnvelope } from '../financeReportTypes'

interface MetricCardProps {
  title: string
  envelope: MetricEnvelope
  icon?: ReactNode
  className?: string
}

/**
 * One {@link MetricEnvelope}, rendered as a KPI tile (FIN-091/092).
 *
 * <p>Not a thin wrapper over the shared `KpiCard`: that component's `value` prop is a plain
 * string or number with no room for "show the reason instead" when there is nothing to format.
 * Every value shown here is exactly what the backend sent — this component adds no arithmetic of
 * its own, so a floor stays a floor rather than being smoothed into a total.
 */
export function MetricCard({ title, envelope, icon, className }: MetricCardProps) {
  const hasValue = envelope.value != null
    && (envelope.availability === 'AVAILABLE' || envelope.availability === 'PARTIALLY_AVAILABLE')

  const formatted = hasValue
    ? (envelope.unit === 'RECEIPTS' ? formatReceiptCount(envelope.value!) : formatRupeesCompact(envelope.value!))
    : null

  return (
    <div
      className={cn(
        'rounded-2xl border border-border bg-card/40 backdrop-blur-sm p-5 shadow-soft-sm',
        className,
      )}
    >
      <div className="flex items-start gap-3">
        {icon && <div className="p-2.5 rounded-xl bg-primary/10 text-primary shrink-0">{icon}</div>}
        <div className="flex-1 min-w-0">
          <p className="text-xs font-bold text-muted-foreground uppercase tracking-widest mb-1">{title}</p>
          {formatted ? (
            <p className="text-2xl font-semibold text-foreground tabular-nums">{formatted}</p>
          ) : (
            <p className="text-2xl font-semibold text-muted-foreground/50" aria-hidden>—</p>
          )}
          <AvailabilityNotice availability={envelope.availability} reason={envelope.reason} className="mt-1.5" />
          {envelope.availability !== 'NOT_AVAILABLE' && envelope.availability !== 'NOT_APPLICABLE'
            && envelope.reconciliation === 'NOT_AVAILABLE' && (
            <p className="text-[11px] text-muted-foreground mt-1">Not yet independently reconciled.</p>
          )}
        </div>
      </div>
    </div>
  )
}
