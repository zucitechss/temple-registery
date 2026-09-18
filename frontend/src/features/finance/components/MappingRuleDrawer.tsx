import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import * as z from 'zod'
import { AlertTriangle, Info, Loader2 } from 'lucide-react'
import { toast } from 'sonner'
import {
  Sheet, SheetContent, SheetHeader, SheetBody, SheetFooter, SheetTitle, SheetDescription,
} from '@/components/ui/sheet'
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import { Switch } from '@/components/ui/switch'
import { Textarea } from '@/components/ui/textarea'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import {
  useCreateMappingRuleMutation,
  useGetNamespacesQuery,
  useListCanonicalValuesQuery,
  useUpdateMappingRuleMutation,
} from '../financeApi'
import { parseApiError } from '../financeErrors'
import { WRITABLE_MAPPING_TYPE, type CanonicalValue, type MappingRule } from '../financeTypes'

/** What the drawer was opened to do. */
export type DrawerIntent =
  | { mode: 'create'; namespace?: string; sourceValue?: string }
  | { mode: 'edit'; rule: MappingRule }

interface MappingRuleDrawerProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  intent: DrawerIntent | null
  sourceSystemId: number
}

/**
 * The colon is structural: it separates the field name from the value, so a field name containing
 * one would split in the wrong place. The backend refuses this too — see `SourceValueKey.compose`
 * — and the rule is stated here only so the user hears it before submitting, not instead.
 */
const schema = z.object({
  namespace: z
    .string()
    .min(1, 'Required — this names the staged field the rule reads')
    .max(100, 'At most 100 characters')
    .refine((v) => !v.includes(':'), 'A field name cannot contain a colon'),
  sourceValue: z
    .string()
    .min(1, 'Required — the value as the source writes it')
    .max(99, 'At most 99 characters'),
  canonicalValue: z.string().min(1, 'Select a revenue category'),
  sourceLabel: z.string().max(400, 'At most 400 characters').optional(),
  priority: z.coerce
    .number({ invalid_type_error: 'Priority must be a number' })
    .int('Priority must be a whole number')
    .min(0, 'Between 0 and 1000')
    .max(1000, 'Between 0 and 1000'),
  active: z.boolean(),
  notes: z.string().max(2000, 'At most 2000 characters').optional(),
})

type FormValues = z.infer<typeof schema>

const EMPTY: FormValues = {
  namespace: '',
  sourceValue: '',
  canonicalValue: '',
  sourceLabel: '',
  priority: 100,
  active: true,
  notes: '',
}

export function MappingRuleDrawer({ open, onOpenChange, intent, sourceSystemId }: MappingRuleDrawerProps) {
  const isEdit = intent?.mode === 'edit'
  const rule = intent?.mode === 'edit' ? intent.rule : null

  const [serverError, setServerError] = useState<string | null>(null)
  const [versionConflict, setVersionConflict] = useState(false)

  const { data: canonicalData, isLoading: loadingCanonical } = useListCanonicalValuesQuery(undefined, {
    skip: !open,
  })
  const { data: namespaceData } = useGetNamespacesQuery(sourceSystemId, { skip: !open })

  const [createRule, { isLoading: creating }] = useCreateMappingRuleMutation()
  const [updateRule, { isLoading: updating }] = useUpdateMappingRuleMutation()
  const isSaving = creating || updating

  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: EMPTY })

  // Reset only when the drawer opens or the subject changes — never while the user is typing, and
  // never after a failed submit, because their work is still in the fields.
  useEffect(() => {
    if (!open || !intent) return
    setServerError(null)
    setVersionConflict(false)
    if (intent.mode === 'edit') {
      form.reset({
        namespace: intent.rule.namespace ?? '',
        sourceValue: intent.rule.sourceValue ?? intent.rule.storedValue,
        canonicalValue: intent.rule.canonicalValue,
        sourceLabel: intent.rule.sourceLabel ?? '',
        priority: intent.rule.priority,
        active: intent.rule.active,
        notes: intent.rule.notes ?? '',
      })
    } else {
      form.reset({
        ...EMPTY,
        namespace: intent.namespace ?? '',
        sourceValue: intent.sourceValue ?? '',
      })
    }
    // form is stable; intent identity drives the reset
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, intent])

  const canonicalValues: CanonicalValue[] = canonicalData?.data ?? []
  const observedNamespaces = namespaceData?.data?.observed ?? []
  const sampledRows = namespaceData?.data?.sampledRows ?? 0

  const currentNamespace = form.watch('namespace')
  // A warning, never a block. The set of fields a source emits is not declared anywhere, so an
  // unrecognised name may simply be one that has not been staged yet (FIN-D-062).
  const namespaceUnrecognised =
    currentNamespace.length > 0 && sampledRows > 0 && !observedNamespaces.includes(currentNamespace)

  const onSubmit = async (values: FormValues) => {
    setServerError(null)
    setVersionConflict(false)

    try {
      const result = isEdit && rule
        ? await updateRule({
            id: rule.id,
            body: {
              version: rule.version,
              namespace: values.namespace,
              sourceValue: values.sourceValue,
              sourceLabel: values.sourceLabel || null,
              canonicalValue: values.canonicalValue,
              priority: values.priority,
              active: values.active,
              notes: values.notes || null,
            },
          }).unwrap()
        : await createRule({
            sourceSystemId,
            mappingType: WRITABLE_MAPPING_TYPE,
            namespace: values.namespace,
            sourceValue: values.sourceValue,
            sourceLabel: values.sourceLabel || null,
            canonicalValue: values.canonicalValue,
            priority: values.priority,
            active: values.active,
            notes: values.notes || null,
          }).unwrap()

      const mutation = result.data
      // The backend's own sentence about what was not changed, shown rather than paraphrased.
      toast.success(
        isEdit ? 'Mapping updated. Existing financial records were not changed.'
               : 'Mapping saved. Existing financial records were not changed.',
        { description: mutation?.historicalEffect, duration: 8000 },
      )
      mutation?.warnings?.forEach((warning) =>
        toast.warning('Saved, but check this', { description: warning, duration: 12000 }),
      )
      onOpenChange(false)
    } catch (error) {
      const parsed = parseApiError(error)
      setVersionConflict(parsed.isVersionConflict)
      setServerError(parsed.message)

      // Field-level messages from a 400 are attached where the user can act on them.
      parsed.fieldErrors.forEach((message) => {
        const lower = message.toLowerCase()
        if (lower.includes('namespace')) form.setError('namespace', { message })
        else if (lower.includes('sourcevalue')) form.setError('sourceValue', { message })
        else if (lower.includes('canonicalvalue')) form.setError('canonicalValue', { message })
        else if (lower.includes('priority')) form.setError('priority', { message })
      })
      // The drawer stays open and the fields keep their values: a refused save must not cost the
      // user their work.
    }
  }

  return (
    <Sheet open={open} onOpenChange={(next) => { if (!isSaving) onOpenChange(next) }}>
      <SheetContent className="w-full sm:max-w-lg flex flex-col">
        <SheetHeader>
          <SheetTitle>{isEdit ? 'Edit mapping rule' : 'New mapping rule'}</SheetTitle>
          <SheetDescription>
            Translate one value from this source system into a canonical revenue category.
          </SheetDescription>
        </SheetHeader>

        <SheetBody className="flex-1 overflow-y-auto">
          <Alert className="mb-5 border-info/40 bg-info/5">
            <Info size={16} className="text-info" aria-hidden />
            <AlertTitle className="text-sm">This applies to future runs only</AlertTitle>
            <AlertDescription className="text-xs leading-relaxed text-muted-foreground">
              Saving a mapping changes how the next pipeline run classifies this value. Figures
              already published keep their current classification until the batch that produced them
              is re-run. Nothing here corrects historical financial data.
            </AlertDescription>
          </Alert>

          {serverError && (
            <Alert
              variant={versionConflict ? 'default' : 'destructive'}
              className={versionConflict ? 'mb-5 border-warning/50 bg-warning/5' : 'mb-5'}
            >
              <AlertTriangle size={16} aria-hidden />
              <AlertTitle className="text-sm">
                {versionConflict ? 'Changed by another user' : 'Not saved'}
              </AlertTitle>
              <AlertDescription className="text-xs leading-relaxed">{serverError}</AlertDescription>
            </Alert>
          )}

          <Form {...form}>
            <form id="mapping-rule-form" onSubmit={form.handleSubmit(onSubmit)} className="space-y-5">
              {/* Not a FormField: the mapping type is fixed, not a value the user supplies. */}
              <div className="space-y-2">
                <Label htmlFor="mapping-type">Mapping type</Label>
                <Input
                  id="mapping-type"
                  value="Revenue category"
                  readOnly
                  disabled
                  aria-readonly
                />
                <p className="text-xs text-muted-foreground">
                  The only type the pipeline reads. Rules of other types cannot be created or
                  edited.
                </p>
              </div>

              <FormField
                control={form.control}
                name="namespace"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Staged field</FormLabel>
                    <FormControl>
                      <Input
                        {...field}
                        list="finance-observed-namespaces"
                        placeholder="SEVA_CODE"
                        autoComplete="off"
                        spellCheck={false}
                        className="font-mono"
                      />
                    </FormControl>
                    <datalist id="finance-observed-namespaces">
                      {observedNamespaces.map((name) => (
                        <option key={name} value={name} />
                      ))}
                    </datalist>
                    <FormDescription className="text-xs">
                      The field in the source record this rule reads. Stored as{' '}
                      <code className="font-mono">
                        {field.value || 'FIELD'}:{form.watch('sourceValue') || 'VALUE'}
                      </code>
                      .
                    </FormDescription>
                    {namespaceUnrecognised && (
                      <p className="flex items-start gap-1.5 text-xs text-warning" role="status">
                        <AlertTriangle size={13} className="mt-px shrink-0" aria-hidden />
                        <span>
                          No staged record from this source carries a field called{' '}
                          <code className="font-mono">{currentNamespace}</code>. The rule will save,
                          but it will not match anything until the source emits that field.
                        </span>
                      </p>
                    )}
                    {sampledRows === 0 && (
                      <p className="text-xs text-muted-foreground">
                        Nothing has been staged for this source yet, so field names cannot be
                        confirmed either way.
                      </p>
                    )}
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="sourceValue"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Source value</FormLabel>
                    <FormControl>
                      <Input
                        {...field}
                        placeholder="430"
                        autoComplete="off"
                        spellCheck={false}
                        className="font-mono"
                      />
                    </FormControl>
                    <FormDescription className="text-xs">
                      Matched exactly. Nothing is trimmed or case-folded, so{' '}
                      <code className="font-mono">Cash</code> and{' '}
                      <code className="font-mono">CASH</code> need separate rules.
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="canonicalValue"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Revenue category</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl>
                        <SelectTrigger aria-label="Revenue category">
                          <SelectValue
                            placeholder={loadingCanonical ? 'Loading…' : 'Select a category'}
                          />
                        </SelectTrigger>
                      </FormControl>
                      <SelectContent>
                        {canonicalValues.map((value) => (
                          <SelectItem key={value.categoryCode} value={value.categoryCode}>
                            {value.categoryName} ({value.categoryCode})
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="sourceLabel"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>
                      Source label <span className="text-muted-foreground">(optional)</span>
                    </FormLabel>
                    <FormControl>
                      <Input {...field} placeholder="How the source names this value" />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="priority"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Priority</FormLabel>
                    <FormControl>
                      <Input {...field} type="number" min={0} max={1000} className="max-w-32" />
                    </FormControl>
                    <FormDescription className="text-xs">
                      Higher wins when several rules match one record. Two matching rules at equal
                      priority produce no category at all, and the record is reported as ambiguous.
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="active"
                render={({ field }) => (
                  <FormItem className="flex items-center justify-between rounded-lg border border-border p-3">
                    <div className="space-y-0.5 pr-4">
                      <FormLabel>Active</FormLabel>
                      <FormDescription className="text-xs">
                        Inactive rules stay listed but are ignored by the pipeline.
                      </FormDescription>
                    </div>
                    <FormControl>
                      <Switch
                        checked={field.value}
                        onCheckedChange={field.onChange}
                        aria-label="Active"
                      />
                    </FormControl>
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="notes"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>
                      Notes <span className="text-muted-foreground">(optional)</span>
                    </FormLabel>
                    <FormControl>
                      <Textarea {...field} rows={3} placeholder="Why this mapping exists" />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </form>
          </Form>
        </SheetBody>

        <SheetFooter className="flex-row justify-end gap-2">
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
            disabled={isSaving}
          >
            Cancel
          </Button>
          <Button type="submit" form="mapping-rule-form" disabled={isSaving || versionConflict}>
            {isSaving && <Loader2 size={15} className="mr-2 animate-spin" aria-hidden />}
            {isSaving ? 'Saving…' : isEdit ? 'Save changes' : 'Create mapping'}
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}
