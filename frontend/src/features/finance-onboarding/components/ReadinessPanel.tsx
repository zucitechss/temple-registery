import { AlertCircle, CheckCircle2, Info, ShieldAlert, TriangleAlert } from 'lucide-react'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { TableSkeleton } from '@/components/feedback/Skeleton/Skeleton'
import type { ReadinessFinding, SourceSystemReadiness } from '../financeOnboardingTypes'

/**
 * What stands between a configured source system and being switched on (FIN-140 slice 140-A).
 *
 * Two things this does on purpose:
 *
 * **It never renders READY as "connected".** The backend sends `connectivityVerified: false` and
 * a sentence explaining that nothing has contacted the source, and that sentence is shown on every
 * verdict including the clean one — most of all on the clean one, which is where the misreading
 * would otherwise happen.
 *
 * **It shows the backend's message verbatim.** Each finding's sentence explains what is wrong and
 * what it would cause. Paraphrasing to fit the layout would lose the second half, which is the
 * half that tells an administrator whether they can safely ignore it.
 */
export function ReadinessPanel({
  readiness,
  isLoading,
  isError,
  errorMessage,
}: {
  readiness?: SourceSystemReadiness
  isLoading: boolean
  isError: boolean
  errorMessage?: string
}) {
  if (isLoading) {
    return <TableSkeleton rows={3} />
  }

  if (isError) {
    return (
      <Alert variant="destructive">
        <AlertCircle className="h-4 w-4" aria-hidden />
        <AlertTitle>Readiness could not be checked</AlertTitle>
        <AlertDescription>
          {errorMessage ?? 'The readiness check could not be run. Nothing has been changed.'}
        </AlertDescription>
      </Alert>
    )
  }

  if (!readiness) {
    return (
      <Alert>
        <Info className="h-4 w-4" aria-hidden />
        <AlertTitle>No source system selected</AlertTitle>
        <AlertDescription>
          Select a source system to see whether its configuration is complete.
        </AlertDescription>
      </Alert>
    )
  }

  const blockers = readiness.findings.filter((f) => f.severity === 'BLOCKED')
  const warnings = readiness.findings.filter((f) => f.severity === 'WARNING')

  return (
    <div className="space-y-4">
      <Verdict readiness={readiness} />

      {blockers.length > 0 && (
        <FindingList
          title={`Must be resolved before this source can be switched on (${blockers.length})`}
          findings={blockers}
          tone="destructive"
        />
      )}

      {warnings.length > 0 && (
        <FindingList
          title={`Worth checking (${warnings.length})`}
          findings={warnings}
          tone="default"
        />
      )}

      <Alert>
        <Info className="h-4 w-4" aria-hidden />
        <AlertTitle>What has not been checked</AlertTitle>
        <AlertDescription>{readiness.connectivityNote}</AlertDescription>
      </Alert>
    </div>
  )
}

function Verdict({ readiness }: { readiness: SourceSystemReadiness }) {
  if (readiness.status === 'BLOCKED') {
    return (
      <Alert variant="destructive">
        <ShieldAlert className="h-4 w-4" aria-hidden />
        <AlertTitle>Configuration incomplete</AlertTitle>
        <AlertDescription>
          {readiness.blockingCount === 1
            ? 'One thing must be resolved before this source system can be switched on.'
            : `${readiness.blockingCount} things must be resolved before this source system can be switched on.`}
        </AlertDescription>
      </Alert>
    )
  }

  if (readiness.status === 'WARNING') {
    return (
      <Alert>
        <TriangleAlert className="h-4 w-4" aria-hidden />
        <AlertTitle>Configuration complete, with notes</AlertTitle>
        <AlertDescription>
          Nothing blocks this source system from being switched on. Read the notes below first —
          each is legal but may not be what was intended.
        </AlertDescription>
      </Alert>
    )
  }

  return (
    <Alert>
      <CheckCircle2 className="h-4 w-4" aria-hidden />
      <AlertTitle>Configuration complete</AlertTitle>
      <AlertDescription>
        Every configuration check passed. This does not mean the source has been contacted — see
        below.
      </AlertDescription>
    </Alert>
  )
}

function FindingList({
  title,
  findings,
  tone,
}: {
  title: string
  findings: ReadinessFinding[]
  tone: 'destructive' | 'default'
}) {
  return (
    <section className="space-y-2">
      <h3 className="text-sm font-semibold text-foreground">{title}</h3>
      <ul className="space-y-2">
        {findings.map((finding) => (
          <li key={`${finding.code}-${finding.subject ?? ''}`}>
            <Alert variant={tone === 'destructive' ? 'destructive' : undefined}>
              <AlertTitle className="flex flex-wrap items-center gap-2">
                <span className="font-mono text-xs uppercase tracking-wide">{finding.code}</span>
                {finding.subject && (
                  <span className="text-xs font-normal opacity-80">{finding.subject}</span>
                )}
              </AlertTitle>
              <AlertDescription>{finding.message}</AlertDescription>
            </Alert>
          </li>
        ))}
      </ul>
    </section>
  )
}
