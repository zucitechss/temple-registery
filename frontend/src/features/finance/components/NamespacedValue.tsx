import { AlertTriangle } from 'lucide-react'
import { cn } from '@/lib/utils'

interface NamespacedValueProps {
  namespace: string | null
  sourceValue: string | null
  /** The single stored column, shown when the two halves cannot be derived from it. */
  storedValue?: string
  /** False when the stored value names no field — the rule can never match anything. */
  wellFormed?: boolean
  className?: string
}

/**
 * A rule's source value, shown as the two things it actually is (FIN-054B).
 *
 * The database holds one string, `SEVA_CODE:430`, whose first colon is structural: the left half
 * names the staged field the rule reads and the right half is the value matched in it. Showing it
 * as one opaque string is how a user comes to believe the colon is part of the value.
 *
 * Nothing is trimmed, case-folded or otherwise adjusted here. Matching is exact on the server, so
 * a value with a stray space is a different value, and tidying it for display would hide the very
 * difference somebody opened this screen to find.
 */
export function NamespacedValue({
  namespace,
  sourceValue,
  storedValue,
  wellFormed = true,
  className,
}: NamespacedValueProps) {
  if (!wellFormed || namespace === null || sourceValue === null) {
    return (
      <span className={cn('inline-flex items-center gap-1.5', className)}>
        <AlertTriangle
          size={14}
          className="shrink-0 text-destructive"
          aria-hidden
        />
        <code className="font-mono text-xs text-foreground break-all">
          {storedValue ?? sourceValue ?? '—'}
        </code>
        <span className="sr-only">
          This value names no staged field, so the rule can never match any record.
        </span>
      </span>
    )
  }

  return (
    <span className={cn('inline-flex flex-wrap items-center gap-1', className)}>
      <span className="rounded-sm bg-muted px-1.5 py-0.5 font-mono text-xs font-medium text-muted-foreground">
        {namespace}
      </span>
      <span className="text-muted-foreground" aria-hidden>
        :
      </span>
      <code className="font-mono text-xs font-semibold text-foreground break-all">
        {sourceValue}
      </code>
      <span className="sr-only">
        Staged field {namespace}, source value {sourceValue}
      </span>
    </span>
  )
}
