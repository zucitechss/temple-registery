import { useMemo, useState } from 'react'
import { AlertCircle, FileSignature, Pencil, Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { TableSkeleton } from '@/components/feedback/Skeleton/Skeleton'
import { useGetCapabilityCatalogueQuery, useListSourceOfTruthQuery } from '../financeOnboardingApi'
import { parseOnboardingError } from '../financeOnboardingErrors'
import { SourceOfTruthDrawer, type SourceOfTruthIntent } from './SourceOfTruthDrawer'
import type { MetricOption, SourceOfTruthDeclaration } from '../financeOnboardingTypes'

/**
 * Which source field is authoritative for each metric, and every version it has ever had
 * (FIN-140-C, ADR-008).
 *
 * <h3>It lists every metric, not only the declared ones</h3>
 *
 * The same argument as the capability matrix: a metric nobody has declared is invisible on the
 * one screen whose job is to declare it. Required metrics are marked, because a source whose
 * revenue is reportable cannot run without them — normalization refuses the batch and every
 * extracted row is rejected.
 *
 * <h3>Superseded versions stay on the screen</h3>
 *
 * They are why the table is versioned. A fact carries the version that produced it, so the
 * declaration that used to be in force is what explains a figure published under it. Hiding the
 * history would leave a reader with the current answer and no way to see that it changed.
 */
export function SourceOfTruthPanel({ sourceSystemId }: { sourceSystemId: number }) {
  const [intent, setIntent] = useState<SourceOfTruthIntent | null>(null)

  const {
    data: catalogueData,
    isLoading: loadingCatalogue,
    isError: catalogueFailed,
    error: catalogueError,
  } = useGetCapabilityCatalogueQuery()

  const {
    data: declarationsData,
    isFetching: loadingDeclarations,
    isError: declarationsFailed,
    error: declarationsError,
  } = useListSourceOfTruthQuery(sourceSystemId)

  const catalogue = catalogueData?.data
  const declarations = useMemo(() => declarationsData?.data ?? [], [declarationsData])

  const byMetric = useMemo(() => {
    const map = new Map<string, SourceOfTruthDeclaration[]>()
    declarations.forEach((declaration) => {
      const existing = map.get(declaration.metric) ?? []
      existing.push(declaration)
      map.set(declaration.metric, existing)
    })
    return map
  }, [declarations])

  if (loadingCatalogue || (loadingDeclarations && declarations.length === 0)) {
    return <TableSkeleton rows={4} />
  }

  if (catalogueFailed || declarationsFailed) {
    return (
      <Alert variant="destructive">
        <AlertCircle className="h-4 w-4" aria-hidden />
        <AlertTitle>Source-of-truth declarations could not be loaded</AlertTitle>
        <AlertDescription>
          {parseOnboardingError(catalogueFailed ? catalogueError : declarationsError).message}
        </AlertDescription>
      </Alert>
    )
  }

  if (!catalogue) return null

  return (
    <div className="space-y-4">
      <p className="text-sm text-muted-foreground">
        Which field in the source carries each figure, and why that one rather than the others
        considered. Saving never edits a declaration — it creates a new version and keeps the old.
      </p>

      <ul className="divide-y rounded-md border">
        {catalogue.metrics.map((metric) => {
          const versions = byMetric.get(metric.metric) ?? []
          const inForce = versions.find((version) => version.inForce) ?? null
          const superseded = versions.filter((version) => !version.inForce)

          return (
            <li key={metric.metric} className="space-y-2 p-3">
              <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
                <div className="min-w-0 space-y-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-mono text-sm">{metric.metric}</span>
                    {metric.required && <Badge variant="secondary">Required</Badge>}
                    {inForce ? (
                      <Badge variant="outline">In force · v{inForce.version}</Badge>
                    ) : (
                      <span className="rounded-full border border-dashed px-2 py-0.5 text-xs text-muted-foreground">
                        Not declared
                      </span>
                    )}
                    {inForce &&
                      (inForce.approved ? (
                        <Badge variant="outline">Signed off</Badge>
                      ) : (
                        <Badge variant="outline">Awaiting sign-off</Badge>
                      ))}
                  </div>

                  {inForce ? (
                    <DeclarationDetail declaration={inForce} />
                  ) : (
                    <p className="text-sm text-muted-foreground">
                      {metric.required
                        ? 'Required. Without it normalization refuses the batch and every extracted row is rejected.'
                        : 'Optional. Undeclared means this source does not record it, and the figure stays absent rather than becoming zero.'}
                    </p>
                  )}
                </div>

                <Button
                  variant="outline"
                  size="sm"
                  className="shrink-0"
                  onClick={() => setIntent({ metric, inForce })}
                >
                  {inForce ? (
                    <>
                      <Pencil className="mr-2 h-3.5 w-3.5" aria-hidden />
                      New version
                    </>
                  ) : (
                    <>
                      <Plus className="mr-2 h-3.5 w-3.5" aria-hidden />
                      Declare
                    </>
                  )}
                </Button>
              </div>

              {superseded.length > 0 && (
                <details className="rounded border bg-muted/30 p-2">
                  <summary className="cursor-pointer text-xs font-medium">
                    {superseded.length} superseded version{superseded.length > 1 ? 's' : ''}
                  </summary>
                  <ul className="mt-2 space-y-2">
                    {superseded.map((version) => (
                      <li key={version.id} className="space-y-1 border-l-2 pl-2">
                        <p className="text-xs font-medium">
                          v{version.version} · {version.effectiveFrom} to {version.effectiveTo}
                        </p>
                        <DeclarationDetail declaration={version} />
                      </li>
                    ))}
                  </ul>
                </details>
              )}
            </li>
          )
        })}
      </ul>

      <SourceOfTruthDrawer
        open={intent !== null}
        onOpenChange={(open) => !open && setIntent(null)}
        intent={intent}
        sourceSystemId={sourceSystemId}
      />
    </div>
  )
}

/**
 * One version, rendered.
 *
 * The rejected alternatives are printed key by key rather than through a fixed shape. The
 * declarations seeded for the first onboarded source carry their own measurement keys, and a
 * renderer expecting a fixed set would drop exactly the measured evidence that gives them force.
 */
function DeclarationDetail({ declaration }: { declaration: SourceOfTruthDeclaration }) {
  return (
    <div className="space-y-1">
      <p className="font-mono text-sm">
        {declaration.sourceObject}.{declaration.sourceField}
      </p>
      {declaration.filterPredicate && (
        <p className="break-words font-mono text-xs text-muted-foreground">
          where {declaration.filterPredicate}
        </p>
      )}
      {declaration.rationale && (
        <p className="text-sm text-muted-foreground">{declaration.rationale}</p>
      )}
      {declaration.rejectedAlternatives.length > 0 && (
        <div className="space-y-1 pt-1">
          <p className="flex items-center gap-1 text-xs font-medium">
            <FileSignature className="h-3 w-3" aria-hidden />
            Rejected alternatives
          </p>
          <ul className="space-y-1">
            {declaration.rejectedAlternatives.map((alternative, index) => (
              <li key={index} className="text-xs text-muted-foreground">
                {Object.entries(alternative).map(([key, value]) => (
                  <span key={key} className="mr-3">
                    <span className="font-medium">{key}:</span> {value}
                  </span>
                ))}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
