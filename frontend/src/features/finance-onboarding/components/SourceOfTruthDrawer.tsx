import { useEffect } from 'react'
import { useFieldArray, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import * as z from 'zod'
import { AlertTriangle, Loader2, Plus, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import {
  Sheet, SheetBody, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@/components/ui/sheet'
import {
  Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage,
} from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { useDeclareSourceOfTruthMutation } from '../financeOnboardingApi'
import { parseOnboardingError } from '../financeOnboardingErrors'
import type { MetricOption, SourceOfTruthDeclaration } from '../financeOnboardingTypes'

/** What the drawer was opened to declare, and what it supersedes. */
export interface SourceOfTruthIntent {
  metric: MetricOption
  /** The version currently in force, if any. Sent back so a stale edit is refused. */
  inForce: SourceOfTruthDeclaration | null
}

const schema = z.object({
  sourceObject: z.string().trim().min(1, 'Required — a declaration naming no object declares nothing.'),
  sourceField: z.string().trim().min(1, 'Required.'),
  effectiveFrom: z.string().min(1, 'Required — it is the date that closes the previous version.'),
  filterPredicate: z.string().optional(),
  rationale: z.string().optional(),
  approved: z.boolean(),
  rejectedAlternatives: z.array(
    z.object({
      object: z.string().trim().min(1, 'Name the object this was read from.'),
      field: z.string().optional(),
      measured: z.string().optional(),
      reason: z.string().trim().min(1, 'Say why it was rejected — that is what stops it being tried again.'),
    }),
  ),
})

type FormValues = z.infer<typeof schema>

/** Today, as the date input wants it. A declaration cannot start later than this. */
function today(): string {
  return new Date().toISOString().slice(0, 10)
}

/**
 * Declare which source field is authoritative for one metric (FIN-140-C, ADR-008).
 *
 * **Saving never edits anything.** It creates a new version and closes the one in force. The
 * drawer says so before the button is pressed, because "save" on a form normally means the
 * opposite and the difference here is the whole point: a figure published last quarter carries
 * the version that produced it, and must stay explicable.
 *
 * The rejected alternatives are given as much room as the declaration itself. For the first
 * onboarded source three columns plausibly represented revenue and disagreed by 41%, and the
 * *more granular* one was the wrong answer — recording the measurement beside the rejection is
 * what stops a later engineer "improving" the query back into the wrong one.
 */
export function SourceOfTruthDrawer({
  open,
  onOpenChange,
  intent,
  sourceSystemId,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  intent: SourceOfTruthIntent | null
  sourceSystemId: number
}) {
  const [declare, { isLoading: saving }] = useDeclareSourceOfTruthMutation()

  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      sourceObject: '',
      sourceField: '',
      effectiveFrom: today(),
      filterPredicate: '',
      rationale: '',
      approved: false,
      rejectedAlternatives: [],
    },
  })

  const alternatives = useFieldArray({ control: form.control, name: 'rejectedAlternatives' })

  useEffect(() => {
    if (!open || !intent) return
    // Pre-filled from the version in force so a revision is a change to it rather than a retype
    // — but never the approval flag, which has to be given again for the new version.
    form.reset({
      sourceObject: intent.inForce?.sourceObject ?? '',
      sourceField: intent.inForce?.sourceField ?? '',
      effectiveFrom: today(),
      filterPredicate: intent.inForce?.filterPredicate ?? '',
      rationale: intent.inForce?.rationale ?? '',
      approved: false,
      rejectedAlternatives: [],
    })
    // Resetting on every form identity change would clear input mid-edit.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, intent])

  const onSubmit = async (values: FormValues) => {
    if (!intent) return
    try {
      await declare({
        sourceSystemId,
        body: {
          metric: intent.metric.metric,
          sourceObject: values.sourceObject.trim(),
          sourceField: values.sourceField.trim(),
          effectiveFrom: values.effectiveFrom,
          filterPredicate: values.filterPredicate?.trim() || undefined,
          rationale: values.rationale?.trim() || undefined,
          approved: values.approved,
          rejectedAlternatives: values.rejectedAlternatives.length
            ? values.rejectedAlternatives.map((entry) => ({
                object: entry.object.trim(),
                field: entry.field?.trim() || undefined,
                measured: entry.measured?.trim() || undefined,
                reason: entry.reason.trim(),
              }))
            : undefined,
          supersedesVersion: intent.inForce?.version,
        },
      }).unwrap()
      toast.success(
        intent.inForce
          ? `Version ${intent.inForce.version + 1} declared. Version ${intent.inForce.version} was kept.`
          : 'Source-of-truth declared.',
      )
      onOpenChange(false)
    } catch (error) {
      const parsed = parseOnboardingError(error)
      toast.error(parsed.message)
      parsed.fieldErrors.forEach((message) => toast.error(message))
    }
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-full sm:max-w-2xl">
        <SheetHeader>
          <SheetTitle>
            {intent?.inForce ? 'Declare a new version' : 'Declare source of truth'}
          </SheetTitle>
          <SheetDescription>
            <span className="font-mono">{intent?.metric.metric}</span> — which field in the source
            carries this, and why that one.
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)}>
            <SheetBody className="space-y-5">
              {intent?.inForce && (
                <Alert>
                  <AlertTriangle className="h-4 w-4" aria-hidden />
                  <AlertTitle>This creates version {intent.inForce.version + 1}</AlertTitle>
                  <AlertDescription>
                    Version {intent.inForce.version} is kept and closed on the date below, not
                    overwritten. Figures already published carry the version that produced them, so
                    a change here is a restatement rather than a correction.
                  </AlertDescription>
                </Alert>
              )}

              <div className="grid gap-4 sm:grid-cols-2">
                <FormField
                  control={form.control}
                  name="sourceObject"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Source object</FormLabel>
                      <FormControl>
                        <Input {...field} className="font-mono" />
                      </FormControl>
                      <FormDescription>The table or view the value is read from.</FormDescription>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                <FormField
                  control={form.control}
                  name="sourceField"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Source field</FormLabel>
                      <FormControl>
                        <Input {...field} className="font-mono" />
                      </FormControl>
                      <FormDescription>
                        Also the key read from the staged payload, so a connector that renames a
                        field must declare the name it emits.
                      </FormDescription>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>

              <FormField
                control={form.control}
                name="effectiveFrom"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Effective from</FormLabel>
                    <FormControl>
                      <Input {...field} type="date" max={today()} />
                    </FormControl>
                    <FormDescription>
                      The date this version starts applying, and the date the previous one closes.
                      It cannot be in the future — a declaration takes effect as soon as it is
                      saved.
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="filterPredicate"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Filter (optional)</FormLabel>
                    <FormControl>
                      <Textarea {...field} rows={2} className="font-mono text-xs" />
                    </FormControl>
                    <FormDescription>
                      Which rows count — cancellations and soft deletes, typically.
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="rationale"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Rationale (optional)</FormLabel>
                    <FormControl>
                      <Textarea {...field} rows={3} />
                    </FormControl>
                    <FormDescription>Why this field, in prose.</FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <div className="space-y-3 rounded-md border p-3">
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <h3 className="text-sm font-medium">Rejected alternatives</h3>
                    <p className="text-xs text-muted-foreground">
                      What else was considered, what it measured, and why it is wrong. This is what
                      stops a later engineer switching to it as an improvement.
                    </p>
                  </div>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="shrink-0"
                    onClick={() =>
                      alternatives.append({ object: '', field: '', measured: '', reason: '' })
                    }
                  >
                    <Plus className="mr-2 h-3.5 w-3.5" aria-hidden />
                    Add
                  </Button>
                </div>

                {alternatives.fields.map((entry, index) => (
                  <div key={entry.id} className="space-y-2 rounded border p-2">
                    <div className="grid gap-2 sm:grid-cols-3">
                      <FormField
                        control={form.control}
                        name={`rejectedAlternatives.${index}.object`}
                        render={({ field }) => (
                          <FormItem>
                            <FormLabel className="text-xs">Object</FormLabel>
                            <FormControl>
                              <Input {...field} className="font-mono text-xs" />
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                        )}
                      />
                      <FormField
                        control={form.control}
                        name={`rejectedAlternatives.${index}.field`}
                        render={({ field }) => (
                          <FormItem>
                            <FormLabel className="text-xs">Field</FormLabel>
                            <FormControl>
                              <Input {...field} className="font-mono text-xs" />
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                        )}
                      />
                      <FormField
                        control={form.control}
                        name={`rejectedAlternatives.${index}.measured`}
                        render={({ field }) => (
                          <FormItem>
                            <FormLabel className="text-xs">Measured</FormLabel>
                            <FormControl>
                              <Input {...field} className="text-xs" />
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                        )}
                      />
                    </div>
                    <FormField
                      control={form.control}
                      name={`rejectedAlternatives.${index}.reason`}
                      render={({ field }) => (
                        <FormItem>
                          <FormLabel className="text-xs">Why it was rejected</FormLabel>
                          <FormControl>
                            <Textarea {...field} rows={2} className="text-xs" />
                          </FormControl>
                          <FormMessage />
                        </FormItem>
                      )}
                    />
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() => alternatives.remove(index)}
                    >
                      <Trash2 className="mr-2 h-3.5 w-3.5" aria-hidden />
                      Remove
                    </Button>
                  </div>
                ))}
              </div>

              <FormField
                control={form.control}
                name="approved"
                render={({ field }) => (
                  <FormItem className="flex flex-row items-start gap-3 space-y-0 rounded-md border p-3">
                    <FormControl>
                      {/* A native checkbox: this is one boolean, and the styled one drags in a
                          ResizeObserver that buys nothing here. */}
                      <input
                        type="checkbox"
                        className="mt-1 h-4 w-4 rounded border-input accent-primary"
                        checked={field.value}
                        onChange={(event) => field.onChange(event.target.checked)}
                        aria-label="Sign this version off"
                      />
                    </FormControl>
                    <div className="space-y-1">
                      <FormLabel>Sign this version off</FormLabel>
                      <FormDescription>
                        Records you and the time against it. Leave it unticked when the evidence is
                        inference rather than confirmation from somebody who runs the source — the
                        declaration applies either way, and the difference is what a reviewer needs
                        to see.
                      </FormDescription>
                    </div>
                  </FormItem>
                )}
              />
            </SheetBody>

            <SheetFooter>
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
                Cancel
              </Button>
              <Button type="submit" disabled={saving}>
                {saving && <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden />}
                {intent?.inForce
                  ? `Declare version ${intent.inForce.version + 1}`
                  : 'Declare source of truth'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  )
}
