import { useMemo, useState } from 'react'
import { AlertCircle, Pencil, Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { StatusBadge } from '@/components/data-display/StatusBadge/StatusBadge'
import { TableSkeleton } from '@/components/feedback/Skeleton/Skeleton'
import {
  useGetCapabilityCatalogueQuery,
  useListCapabilityDeclarationsQuery,
} from '../financeOnboardingApi'
import { parseOnboardingError } from '../financeOnboardingErrors'
import {
  CapabilityDeclarationDrawer,
  type CapabilityIntent,
} from './CapabilityDeclarationDrawer'
import type { CapabilityDeclaration, DataAvailability } from '../financeOnboardingTypes'

/**
 * What this source system has been declared able to answer (FIN-140-B).
 *
 * <h3>It lists the whole catalogue, not just what is declared</h3>
 *
 * An undeclared capability and one declared NOT_AVAILABLE look identical to every reader
 * downstream, and they are different statements — the second says the source does not record
 * something, the first says nobody has looked. A panel showing only declared rows would make the
 * gap invisible on the one screen whose job is to close it, so every canonical capability appears
 * and undeclared ones are called out.
 *
 * <h3>The list comes from the server</h3>
 *
 * The capabilities and the availability vocabulary are both fetched. A hard-coded list here would
 * be a second source of truth that stops offering any capability added to the backend, silently.
 */
export function CapabilityMatrixPanel({ sourceSystemId }: { sourceSystemId: number }) {
  const [intent, setIntent] = useState<CapabilityIntent | null>(null)

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
  } = useListCapabilityDeclarationsQuery(sourceSystemId)

  const catalogue = catalogueData?.data
  const declarations = useMemo(() => declarationsData?.data ?? [], [declarationsData])

  const byCapability = useMemo(() => {
    const map = new Map<string, CapabilityDeclaration>()
    declarations.forEach((declaration) => map.set(declaration.capability, declaration))
    return map
  }, [declarations])

  if (loadingCatalogue || (loadingDeclarations && declarations.length === 0)) {
    return <TableSkeleton rows={5} />
  }

  if (catalogueFailed || declarationsFailed) {
    return (
      <Alert variant="destructive">
        <AlertCircle className="h-4 w-4" aria-hidden />
        <AlertTitle>Capabilities could not be loaded</AlertTitle>
        <AlertDescription>
          {parseOnboardingError(catalogueFailed ? catalogueError : declarationsError).message}
        </AlertDescription>
      </Alert>
    )
  }

  if (!catalogue) return null

  const undeclared = catalogue.capabilities.filter((o) => !byCapability.has(o.capability)).length

  return (
    <div className="space-y-4">
      <p className="text-sm text-muted-foreground">
        {declarations.length} of {catalogue.capabilities.length} declared.
        {undeclared > 0 && (
          <>
            {' '}
            An undeclared capability is not the same statement as one declared not available — the
            second says the source does not record it, the first says nobody has checked.
          </>
        )}
      </p>

      <ul className="divide-y rounded-md border">
        {catalogue.capabilities.map((option) => {
          const declaration = byCapability.get(option.capability)
          return (
            <li
              key={option.capability}
              className="flex flex-col gap-2 p-3 sm:flex-row sm:items-start sm:justify-between"
            >
              <div className="min-w-0 space-y-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-mono text-sm">
                    {option.capability.replace(/_/g, ' ')}
                  </span>
                  {declaration ? (
                    <StatusBadge status={badgeStatusFor(declaration.availability)} />
                  ) : (
                    <span className="rounded-full border border-dashed px-2 py-0.5 text-xs text-muted-foreground">
                      Not declared
                    </span>
                  )}
                </div>
                {declaration?.availabilityReason && (
                  <p className="text-sm text-muted-foreground">{declaration.availabilityReason}</p>
                )}
                {declaration?.coverageFrom && (
                  <p className="text-xs text-muted-foreground">
                    Coverage {declaration.coverageFrom} to {declaration.coverageTo ?? 'present'}
                  </p>
                )}
                {declaration && declaration.knownGaps.length > 0 && (
                  <p className="text-xs text-muted-foreground">
                    Known gaps: {declaration.knownGaps.join('; ')}
                  </p>
                )}
              </div>

              <Button
                variant="outline"
                size="sm"
                className="shrink-0"
                onClick={() =>
                  setIntent(
                    declaration
                      ? {
                          mode: 'edit',
                          declaration,
                          drivesRevenueRequirements: option.drivesRevenueRequirements,
                        }
                      : {
                          mode: 'declare',
                          capability: option.capability,
                          drivesRevenueRequirements: option.drivesRevenueRequirements,
                        },
                  )
                }
              >
                {declaration ? (
                  <>
                    <Pencil className="mr-2 h-3.5 w-3.5" aria-hidden />
                    Revise
                  </>
                ) : (
                  <>
                    <Plus className="mr-2 h-3.5 w-3.5" aria-hidden />
                    Declare
                  </>
                )}
              </Button>
            </li>
          )
        })}
      </ul>

      <CapabilityDeclarationDrawer
        open={intent !== null}
        onOpenChange={(open) => !open && setIntent(null)}
        intent={intent}
        sourceSystemId={sourceSystemId}
        availabilities={catalogue.availabilities}
      />
    </div>
  )
}

/**
 * Maps an availability onto the shared badge's vocabulary.
 *
 * `NOT_AVAILABLE` is deliberately not styled as an error: the source not recording something is a
 * fact about the temple, not a fault in the configuration.
 */
function badgeStatusFor(availability: DataAvailability): string {
  switch (availability) {
    case 'AVAILABLE':
      return 'ACTIVE'
    case 'PARTIALLY_AVAILABLE':
      return 'PENDING'
    default:
      return 'INACTIVE'
  }
}
