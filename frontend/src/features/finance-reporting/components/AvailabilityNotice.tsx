import { AlertTriangle, Ban, CircleHelp } from 'lucide-react'
import { cn } from '@/lib/utils'
import type { DataAvailability } from '../financeReportTypes'

interface AvailabilityNoticeProps {
  availability: DataAvailability
  /** The backend's own declared reason. Rendered verbatim — never paraphrased. */
  reason: string | null
  className?: string
}

/**
 * Renders why a figure is missing or partial, and never a substitute number (FIN-092).
 *
 * <p>This is the component that makes ADR-007 real on screen: "we do not know" and "there were
 * none" must never look the same to a reader. Every caller of this component passes it the
 * backend's own `reason` string, written for a person, rather than composing its own guess.
 *
 * <p>Renders nothing for `AVAILABLE` — a present figure needs no notice.
 */
export function AvailabilityNotice({ availability, reason, className }: AvailabilityNoticeProps) {
  if (availability === 'AVAILABLE') return null

  const config = {
    PARTIALLY_AVAILABLE: {
      icon: AlertTriangle,
      tone: 'text-amber-700 dark:text-amber-400',
      label: 'Partial',
    },
    NOT_AVAILABLE: {
      icon: Ban,
      tone: 'text-muted-foreground',
      label: 'Not available',
    },
    NOT_APPLICABLE: {
      icon: CircleHelp,
      tone: 'text-muted-foreground',
      label: 'Not applicable',
    },
  }[availability]

  const Icon = config.icon

  return (
    <div className={cn('flex items-start gap-1.5 text-xs', config.tone, className)}>
      <Icon size={13} className="mt-0.5 shrink-0" aria-hidden />
      <span>
        <span className="font-medium">{config.label}.</span>{' '}
        {reason ?? 'No reason was given.'}
      </span>
    </div>
  )
}
