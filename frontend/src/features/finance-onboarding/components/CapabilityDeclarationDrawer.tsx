import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import * as z from 'zod'
import { Info, Loader2 } from 'lucide-react'
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
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import {
  useDeclareCapabilityMutation,
  useUpdateCapabilityDeclarationMutation,
} from '../financeOnboardingApi'
import { parseOnboardingError } from '../financeOnboardingErrors'
import type {
  CapabilityDeclaration,
  DataAvailability,
  FinanceCapabilityCode,
} from '../financeOnboardingTypes'

/** What the drawer was opened to do. */
export type CapabilityIntent =
  | { mode: 'declare'; capability: FinanceCapabilityCode; drivesRevenueRequirements: boolean }
  | { mode: 'edit'; declaration: CapabilityDeclaration; drivesRevenueRequirements: boolean }

/**
 * The reason is required unless the capability is available, mirroring the server.
 *
 * Stated here so the user hears it before submitting, not instead: the backend refuses the same
 * thing with the same reasoning, because the sentence is rendered to a reader verbatim where a
 * figure would otherwise appear.
 */
const schema = z
  .object({
    availability: z.enum(['AVAILABLE', 'PARTIALLY_AVAILABLE', 'NOT_AVAILABLE', 'NOT_APPLICABLE']),
    availabilityReason: z.string().max(4000, 'At most 4000 characters').optional(),
    coverageFrom: z.string().optional(),
    coverageTo: z.string().optional(),
    knownGaps: z.string().max(4000, 'At most 4000 characters').optional(),
  })
  .refine(
    (v) => v.availability === 'AVAILABLE' || Boolean(v.availabilityReason?.trim()),
    {
      path: ['availabilityReason'],
      message:
        'Required — this sentence is shown to a reader where a figure would otherwise appear.',
    },
  )
  .refine(
    (v) => !v.coverageFrom || !v.coverageTo || v.coverageTo >= v.coverageFrom,
    { path: ['coverageTo'], message: 'Coverage cannot end before it begins.' },
  )

type FormValues = z.infer<typeof schema>

const AVAILABILITY_HELP: Record<DataAvailability, string> = {
  AVAILABLE: 'The source records this in full for the declared coverage window.',
  PARTIALLY_AVAILABLE: 'Recorded for part of the window, or with known gaps inside it.',
  NOT_AVAILABLE: 'The source system does not record this at all. It is never reported as zero.',
  NOT_APPLICABLE: 'The question does not arise for this temple.',
}

/**
 * Declare or revise what a source system can answer for one capability (FIN-140-B).
 *
 * The capability itself is never editable. It is identity — declarations are unique per temple and
 * capability — so changing it would not edit this declaration but silently become a different one.
 */
export function CapabilityDeclarationDrawer({
  open,
  onOpenChange,
  intent,
  sourceSystemId,
  availabilities,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  intent: CapabilityIntent | null
  sourceSystemId: number
  availabilities: DataAvailability[]
}) {
  const [declare, { isLoading: declaring }] = useDeclareCapabilityMutation()
  const [update, { isLoading: updating }] = useUpdateCapabilityDeclarationMutation()
  const isSaving = declaring || updating

  const existing = intent?.mode === 'edit' ? intent.declaration : null
  const capability =
    intent?.mode === 'edit' ? intent.declaration.capability : intent?.capability ?? ''

  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      availability: 'AVAILABLE',
      availabilityReason: '',
      coverageFrom: '',
      coverageTo: '',
      knownGaps: '',
    },
  })

  useEffect(() => {
    if (!open || !intent) return
    form.reset({
      availability: existing?.availability ?? 'AVAILABLE',
      availabilityReason: existing?.availabilityReason ?? '',
      coverageFrom: existing?.coverageFrom ?? '',
      coverageTo: existing?.coverageTo ?? '',
      knownGaps: (existing?.knownGaps ?? []).join('\n'),
    })
    // Resetting on every form identity change would clear input mid-edit.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, intent])

  const onSubmit = async (values: FormValues) => {
    const body = {
      availability: values.availability,
      availabilityReason: values.availabilityReason?.trim() || undefined,
      coverageFrom: values.coverageFrom || undefined,
      coverageTo: values.coverageTo || undefined,
      knownGaps: values.knownGaps
        ? values.knownGaps.split('\n').map((g) => g.trim()).filter(Boolean)
        : undefined,
    }

    try {
      if (intent?.mode === 'edit') {
        await update({
          sourceSystemId,
          declarationId: intent.declaration.id,
          body: { ...body, version: intent.declaration.version },
        }).unwrap()
        toast.success('Capability declaration updated.')
      } else if (intent?.mode === 'declare') {
        await declare({
          sourceSystemId,
          body: { ...body, capability: intent.capability },
        }).unwrap()
        toast.success('Capability declared.')
      }
      onOpenChange(false)
    } catch (error) {
      const parsed = parseOnboardingError(error)
      toast.error(parsed.message)
      parsed.fieldErrors.forEach((message) => toast.error(message))
    }
  }

  const availability = form.watch('availability')

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-full sm:max-w-xl">
        <SheetHeader>
          <SheetTitle>
            {intent?.mode === 'edit' ? 'Revise declaration' : 'Declare capability'}
          </SheetTitle>
          <SheetDescription>
            <span className="font-mono">{capability.replace(/_/g, ' ')}</span> — what this source
            system can tell the platform, and why when it cannot.
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)}>
            <SheetBody className="space-y-5">
              {intent?.drivesRevenueRequirements && (
                <Alert>
                  <Info className="h-4 w-4" aria-hidden />
                  <AlertTitle>This one pulls further configuration in</AlertTitle>
                  <AlertDescription>
                    Declaring it available or partially available makes source-of-truth
                    declarations and mapping rules required before this source can be switched on.
                  </AlertDescription>
                </Alert>
              )}

              <FormField
                control={form.control}
                name="availability"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Availability</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl>
                        <SelectTrigger aria-label="Availability">
                          <SelectValue />
                        </SelectTrigger>
                      </FormControl>
                      <SelectContent>
                        {availabilities.map((value) => (
                          <SelectItem key={value} value={value}>
                            {value.replace(/_/g, ' ')}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <FormDescription>{AVAILABILITY_HELP[field.value]}</FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="availabilityReason"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>
                      Reason{availability === 'AVAILABLE' ? ' (optional)' : ''}
                    </FormLabel>
                    <FormControl>
                      <Textarea {...field} rows={3} />
                    </FormControl>
                    <FormDescription>
                      Shown to a reader verbatim where a figure would otherwise appear. Say what was
                      measured, not merely that data is unavailable.
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <div className="grid gap-4 sm:grid-cols-2">
                <FormField
                  control={form.control}
                  name="coverageFrom"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Coverage from</FormLabel>
                      <FormControl>
                        <Input {...field} type="date" />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                <FormField
                  control={form.control}
                  name="coverageTo"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Coverage to</FormLabel>
                      <FormControl>
                        <Input {...field} type="date" />
                      </FormControl>
                      <FormDescription>
                        The latest date present in the source — never the last sync time.
                      </FormDescription>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>

              <FormField
                control={form.control}
                name="knownGaps"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Known gaps (optional)</FormLabel>
                    <FormControl>
                      <Textarea {...field} rows={3} placeholder="One gap per line" />
                    </FormControl>
                    <FormDescription>
                      Documented holes inside the coverage window. One per line.
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </SheetBody>

            <SheetFooter>
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
                Cancel
              </Button>
              <Button type="submit" disabled={isSaving}>
                {isSaving && <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden />}
                {intent?.mode === 'edit' ? 'Save declaration' : 'Declare capability'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  )
}
