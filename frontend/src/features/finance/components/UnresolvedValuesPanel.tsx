import { AlertCircle, Info, Plus, RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { EmptyState } from '@/components/feedback/EmptyState/EmptyState'
import { TableSkeleton } from '@/components/feedback/Skeleton/Skeleton'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { useListUnresolvedValuesQuery } from '../financeApi'
import { parseApiError } from '../financeErrors'
import { NamespacedValue } from './NamespacedValue'
import { UNRESOLVED_OUTCOMES, type MappingOutcome } from '../financeTypes'

const OUTCOME_LABELS: Record<string, string> = {
  UNMAPPED: 'Unmapped — no rule matches',
  AMBIGUOUS: 'Ambiguous — rules tie on priority',
  INVALID_CONFIGURATION: 'Invalid — rule names an unknown category',
  NOT_APPLICABLE: 'Not applicable — nothing to map',
}

const OUTCOME_HELP: Record<string, string> = {
  UNMAPPED:
    'Real income whose kind has not been established. Adding a rule for the value is the fix.',
  AMBIGUOUS:
    'Several rules matched at the same priority, so no category was assigned. Give the more specific rule a higher priority.',
  INVALID_CONFIGURATION:
    'A rule points at a revenue category that no longer exists. Edit the rule to name a current category.',
  NOT_APPLICABLE:
    'The record carried none of the fields this source’s rules read, or carried them with no value.',
}

interface UnresolvedValuesPanelProps {
  sourceSystemId: number
  outcome: MappingOutcome
  onOutcomeChange: (outcome: MappingOutcome) => void
  canCreate: boolean
  onCreateFromValue: (namespace: string, sourceValue: string) => void
}

/**
 * What the most recent batch could not classify (FIN-054B).
 *
 * <p>The counts are **one batch's**, and the batch is named. Adding them up across batches would
 * inflate them: re-extracting a period stages the same records again, so a value present in three
 * batches would be shown as costing three times the records it costs. The backend scopes this for
 * the same reason (FIN-D-065), and this panel says which batch it is looking at rather than
 * implying a live figure.
 *
 * <p>An empty list with no batch is **not** the same as nothing unresolved — it means nothing has
 * been mapped yet, and the two are shown differently.
 */
export function UnresolvedValuesPanel({
  sourceSystemId,
  outcome,
  onOutcomeChange,
  canCreate,
  onCreateFromValue,
}: UnresolvedValuesPanelProps) {
  const { data, isFetching, isError, error, refetch } = useListUnresolvedValuesQuery({
    sourceSystemId,
    outcome,
  })

  const unresolved = data?.data
  const values = unresolved?.values ?? []
  const measured = unresolved?.syncBatchId != null

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div className="space-y-1.5">
          <label htmlFor="unresolved-outcome" className="text-sm font-medium text-foreground">
            Outcome
          </label>
          <Select value={outcome} onValueChange={(v) => onOutcomeChange(v as MappingOutcome)}>
            <SelectTrigger id="unresolved-outcome" className="w-full sm:w-80">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {UNRESOLVED_OUTCOMES.map((value) => (
                <SelectItem key={value} value={value}>
                  {OUTCOME_LABELS[value]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <Button variant="outline" size="sm" onClick={() => refetch()} disabled={isFetching}>
          <RefreshCw size={14} className={isFetching ? 'mr-2 animate-spin' : 'mr-2'} aria-hidden />
          Refresh
        </Button>
      </div>

      <p className="text-sm text-muted-foreground">{OUTCOME_HELP[outcome]}</p>

      {isError && (
        <Alert variant="destructive">
          <AlertCircle size={16} aria-hidden />
          <AlertDescription className="flex flex-wrap items-center justify-between gap-3">
            <span className="text-sm">{parseApiError(error).message}</span>
            <Button variant="outline" size="sm" onClick={() => refetch()}>
              Try again
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {isFetching && !data && <TableSkeleton rows={4} />}

      {!isFetching && !isError && !measured && (
        <EmptyState
          title="Nothing has been mapped for this source yet"
          description="No pipeline run has recorded a decision here, so there is nothing to report. This is not the same as having no unresolved values."
          icon={<Info size={28} aria-hidden />}
        />
      )}

      {!isError && measured && values.length === 0 && (
        <EmptyState
          title={`No ${outcome.toLowerCase().replace(/_/g, ' ')} values in batch ${unresolved?.syncBatchId}`}
          description="Every record this batch decided reached a category."
        />
      )}

      {!isError && measured && values.length > 0 && (
        <>
          <Alert className="border-info/40 bg-info/5">
            <Info size={16} className="text-info" aria-hidden />
            <AlertDescription className="text-xs leading-relaxed text-muted-foreground">
              Counts are from sync batch <strong>{unresolved?.syncBatchId}</strong>
              {unresolved?.observedAt && (
                <> , decided {new Date(unresolved.observedAt).toLocaleString()}</>
              )}
              . They are not a live figure, and they will not change until the batch is processed
              again.
            </AlertDescription>
          </Alert>

          {/* Table on wide screens */}
          <div className="hidden overflow-hidden rounded-lg border border-border md:block">
            <table className="w-full text-sm">
              <caption className="sr-only">
                Values batch {unresolved?.syncBatchId} could not classify, most affected first
              </caption>
              <thead className="border-b border-border bg-muted/50">
                <tr>
                  <th scope="col" className="px-4 py-3 text-left font-semibold">Source value</th>
                  <th scope="col" className="px-4 py-3 text-right font-semibold">Records affected</th>
                  <th scope="col" className="px-4 py-3 text-left font-semibold">Last seen</th>
                  <th scope="col" className="px-4 py-3 text-right font-semibold">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {values.map((value) => (
                  <tr key={`${value.namespace}:${value.sourceValue}`} className="hover:bg-muted/30">
                    <td className="px-4 py-3">
                      <NamespacedValue
                        namespace={value.namespace}
                        sourceValue={value.sourceValue}
                        wellFormed={value.namespace !== null && value.sourceValue !== null}
                      />
                    </td>
                    <td className="px-4 py-3 text-right font-semibold tabular-nums">
                      {value.affected.toLocaleString()}
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">
                      {value.lastSeenAt ? new Date(value.lastSeenAt).toLocaleDateString() : '—'}
                    </td>
                    <td className="px-4 py-3 text-right">
                      {canCreate && value.namespace && value.sourceValue ? (
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => onCreateFromValue(value.namespace!, value.sourceValue!)}
                        >
                          <Plus size={14} className="mr-1" aria-hidden />
                          Create rule
                        </Button>
                      ) : (
                        <span className="text-xs text-muted-foreground">
                          {canCreate ? 'No field to key on' : '—'}
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Cards on narrow screens */}
          <ul className="space-y-3 md:hidden">
            {values.map((value) => (
              <li
                key={`${value.namespace}:${value.sourceValue}`}
                className="rounded-lg border border-border bg-card p-4 space-y-3"
              >
                <NamespacedValue
                  namespace={value.namespace}
                  sourceValue={value.sourceValue}
                  wellFormed={value.namespace !== null && value.sourceValue !== null}
                />
                <div className="flex items-center justify-between text-xs text-muted-foreground">
                  <span>
                    <strong className="text-foreground">{value.affected.toLocaleString()}</strong>{' '}
                    records affected
                  </span>
                  <span>
                    {value.lastSeenAt ? new Date(value.lastSeenAt).toLocaleDateString() : '—'}
                  </span>
                </div>
                {canCreate && value.namespace && value.sourceValue && (
                  <Button
                    size="sm"
                    variant="outline"
                    className="w-full"
                    onClick={() => onCreateFromValue(value.namespace!, value.sourceValue!)}
                  >
                    <Plus size={14} className="mr-1" aria-hidden />
                    Create rule
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}
