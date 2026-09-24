import { useState } from 'react'
import { AlertCircle, Info, Loader2, Power, PowerOff, TriangleAlert } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Textarea } from '@/components/ui/textarea'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { useSetSourceSystemActivationMutation } from '../financeOnboardingApi'
import { parseOnboardingError } from '../financeOnboardingErrors'
import type { SourceSystemReadiness } from '../financeOnboardingTypes'

/**
 * Whether the platform is permitted to contact this source system (FIN-140-D).
 *
 * <h3>The word "enabled" is doing careful work here</h3>
 *
 * This panel sets one boolean. It does not start a synchronisation, and today nothing in
 * production reads the flag at all — there is no deployed connector and no worker trigger. Every
 * label says "enable for sync" rather than "activate", "connect" or "go live", and the state text
 * says what has *not* happened, for the same reason the readiness panel prints its connectivity
 * note on the clean verdict: the reassuring state is where the misreading happens.
 *
 * <h3>The button being disabled is not the protection</h3>
 *
 * It is disabled while readiness is blocked because offering an action that will be refused is bad
 * manners, not because it is a control. The backend re-evaluates readiness inside the transaction
 * and refuses there. If this screen's verdict is stale, the server wins and the error is shown.
 */
export function ActivationPanel({
  sourceSystemId,
  enabledForSync,
  readiness,
}: {
  sourceSystemId: number
  enabledForSync: boolean
  readiness?: SourceSystemReadiness
}) {
  const [confirmingDisable, setConfirmingDisable] = useState(false)
  const [reason, setReason] = useState('')
  const [failure, setFailure] = useState<string | null>(null)
  const [setActivation, { isLoading: saving }] = useSetSourceSystemActivationMutation()

  const blocked = readiness ? readiness.blockingCount > 0 : false
  const readinessUnknown = readiness === undefined

  const submit = async (enabled: boolean, why?: string) => {
    setFailure(null)
    try {
      const result = await setActivation({
        sourceSystemId,
        body: enabled ? { enabled } : { enabled, reason: why },
      }).unwrap()

      const data = result.data
      if (data && !data.changed) {
        toast.info(
          `No change — this source system was already ${data.enabledForSync ? 'enabled' : 'disabled'}.`,
        )
      } else {
        toast.success(
          enabled
            ? 'Enabled for future synchronisation. Nothing has been contacted.'
            : 'Disabled. Configuration and declaration history are retained.',
        )
      }
      data?.warnings?.forEach((warning) => toast.warning(warning))
      setConfirmingDisable(false)
      setReason('')
    } catch (error) {
      const parsed = parseOnboardingError(error)
      setFailure(parsed.message)
      toast.error(parsed.message)
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <span className="text-sm font-medium">Status:</span>
        {enabledForSync ? (
          <Badge variant="outline">Enabled for sync</Badge>
        ) : (
          <Badge variant="secondary">Not enabled</Badge>
        )}
      </div>

      {enabledForSync ? (
        <Alert>
          <Info className="h-4 w-4" aria-hidden />
          <AlertTitle>Enabled for future synchronisation</AlertTitle>
          <AlertDescription>
            This grants permission only. No source connection is performed by this action, no
            connector implementation has been deployed yet, and nothing currently reads this
            setting — so no data is being synchronised as a result of it.
          </AlertDescription>
        </Alert>
      ) : (
        <Alert>
          <Info className="h-4 w-4" aria-hidden />
          <AlertTitle>Not enabled</AlertTitle>
          <AlertDescription>
            The platform will not contact this source system. Enabling it grants permission for
            future synchronisation; it does not start one and does not connect to anything.
          </AlertDescription>
        </Alert>
      )}

      {blocked && !enabledForSync && (
        <Alert variant="destructive">
          <TriangleAlert className="h-4 w-4" aria-hidden />
          <AlertTitle>Cannot be enabled yet</AlertTitle>
          <AlertDescription>
            {readiness?.blockingCount === 1
              ? 'One readiness check is blocking. It is listed below under readiness.'
              : `${readiness?.blockingCount} readiness checks are blocking. They are listed below under readiness.`}
          </AlertDescription>
        </Alert>
      )}

      {failure && (
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" aria-hidden />
          <AlertTitle>Nothing was changed</AlertTitle>
          <AlertDescription>{failure}</AlertDescription>
        </Alert>
      )}

      {!enabledForSync && (
        <Button
          onClick={() => submit(true)}
          disabled={saving || blocked || readinessUnknown}
          title={blocked ? 'Resolve the blocking readiness checks first.' : undefined}
        >
          {saving ? (
            <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden />
          ) : (
            <Power className="mr-2 h-4 w-4" aria-hidden />
          )}
          Enable for sync
        </Button>
      )}

      {enabledForSync && !confirmingDisable && (
        <Button variant="outline" onClick={() => setConfirmingDisable(true)} disabled={saving}>
          <PowerOff className="mr-2 h-4 w-4" aria-hidden />
          Disable
        </Button>
      )}

      {enabledForSync && confirmingDisable && (
        <div className="space-y-3 rounded-md border p-3">
          <div>
            <label htmlFor="disable-reason" className="text-sm font-medium">
              Why is this being disabled?
            </label>
            <p className="text-xs text-muted-foreground">
              Recorded in the audit trail, which is read by people who were not in the room.
              Nothing is deleted — capability and source-of-truth declarations are all retained.
            </p>
          </div>
          <Textarea
            id="disable-reason"
            rows={2}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
          <div className="flex flex-wrap gap-2">
            <Button
              variant="destructive"
              disabled={saving || reason.trim().length === 0}
              onClick={() => submit(false, reason.trim())}
            >
              {saving && <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden />}
              Confirm disable
            </Button>
            <Button
              variant="outline"
              onClick={() => {
                setConfirmingDisable(false)
                setReason('')
              }}
            >
              Cancel
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}
