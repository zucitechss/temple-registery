import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import * as z from 'zod'
import { Info, Loader2, ShieldAlert } from 'lucide-react'
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
import { useRegisterSourceSystemMutation } from '../financeOnboardingApi'
import { parseOnboardingError } from '../financeOnboardingErrors'
import { CONNECTOR_TYPES, SOURCE_TECHNOLOGIES } from '../financeOnboardingTypes'

/**
 * The system code is quoted in support conversations, log lines and audit detail. Two codes
 * differing only by case or a stray space are two codes nobody can search for reliably, so the
 * same shape the backend enforces is stated here — before submitting, not instead of.
 */
const schema = z.object({
  templeId: z
    .string()
    .min(1, 'Required — which temple this source system belongs to')
    .refine((v) => /^\d+$/.test(v.trim()), 'Enter a numeric temple id'),
  systemCode: z
    .string()
    .min(1, 'Required')
    .max(50, 'At most 50 characters')
    .refine((v) => /^[A-Z0-9_]+$/.test(v), 'Upper-case letters, digits and underscore only'),
  systemName: z.string().min(1, 'Required').max(200, 'At most 200 characters'),
  sourceTechnology: z.enum(SOURCE_TECHNOLOGIES),
  connectorType: z.enum(CONNECTOR_TYPES),
  connectorBean: z.string().min(1, 'Required').max(150, 'At most 150 characters'),
  sourceTempleCode: z.string().max(50, 'At most 50 characters').optional(),
  sourceDatabaseName: z.string().max(100, 'At most 100 characters').optional(),
  credentialRef: z.string().max(200, 'At most 200 characters').optional(),
  notes: z.string().max(4000, 'At most 4000 characters').optional(),
})

type FormValues = z.infer<typeof schema>

/**
 * Register a temple's finance source system (FIN-140 slice 140-A).
 *
 * Two statements this form makes at the point of edit, because a user who sees only "Saved" would
 * reasonably conclude something else happened:
 *
 * - **Registering does not connect anything.** The row is created switched off, and nothing here
 *   contacts a temple.
 * - **Naming a connector does not create one.** The bean is resolved inside the sync worker, and
 *   only if a release has deployed a class registered under that name.
 *
 * The same discipline as the Source Mapper's `historicalEffect` sentence: say what saving does
 * not do, where the person is deciding to save.
 */
export function RegisterSourceSystemDrawer({
  open,
  onOpenChange,
  onRegistered,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onRegistered?: (id: number) => void
}) {
  const [register, { isLoading }] = useRegisterSourceSystemMutation()

  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      templeId: '',
      systemCode: '',
      systemName: '',
      sourceTechnology: 'SQL_SERVER',
      connectorType: 'PULL_JDBC',
      connectorBean: '',
      sourceTempleCode: '',
      sourceDatabaseName: '',
      credentialRef: '',
      notes: '',
    },
  })

  useEffect(() => {
    if (open) form.reset()
    // form is stable across renders; resetting on every identity change would clear user input
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  const onSubmit = async (values: FormValues) => {
    try {
      const result = await register({
        templeId: Number(values.templeId.trim()),
        systemCode: values.systemCode,
        systemName: values.systemName,
        sourceTechnology: values.sourceTechnology,
        connectorType: values.connectorType,
        connectorBean: values.connectorBean,
        sourceTempleCode: values.sourceTempleCode || undefined,
        sourceDatabaseName: values.sourceDatabaseName || undefined,
        credentialRef: values.credentialRef || undefined,
        notes: values.notes || undefined,
      }).unwrap()

      toast.success('Source system registered. Synchronisation is off until it is activated.')
      onOpenChange(false)
      if (result.data?.id != null) onRegistered?.(result.data.id)
    } catch (error) {
      const parsed = parseOnboardingError(error)
      toast.error(parsed.message)
      parsed.fieldErrors.forEach((message) => toast.error(message))
    }
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-full sm:max-w-xl">
        <SheetHeader>
          <SheetTitle>Register a source system</SheetTitle>
          <SheetDescription>
            Record which external system supplies a temple's financial data.
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)}>
            <SheetBody className="space-y-5">
              <Alert>
                <Info className="h-4 w-4" aria-hidden />
                <AlertTitle>Registering does not connect anything</AlertTitle>
                <AlertDescription>
                  The source is created switched off. Nothing here contacts the temple's system,
                  and naming a connector does not create one — the connector is a deployed
                  component, resolved only by the sync worker.
                </AlertDescription>
              </Alert>

              <FormField
                control={form.control}
                name="templeId"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Temple id</FormLabel>
                    <FormControl>
                      <Input {...field} inputMode="numeric" placeholder="300001" />
                    </FormControl>
                    <FormDescription>
                      The registry's id for the temple this system belongs to.
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="systemCode"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>System code</FormLabel>
                    <FormControl>
                      <Input {...field} placeholder="KOLSOHAM" autoCapitalize="characters" />
                    </FormControl>
                    <FormDescription>
                      A short stable code, unique for this temple. Quoted in support and audit
                      records, so it cannot be changed later.
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="systemName"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>System name</FormLabel>
                    <FormControl>
                      <Input {...field} placeholder="Temple operational system" />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <div className="grid gap-4 sm:grid-cols-2">
                <FormField
                  control={form.control}
                  name="sourceTechnology"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Source technology</FormLabel>
                      <Select onValueChange={field.onChange} value={field.value}>
                        <FormControl>
                          <SelectTrigger aria-label="Source technology">
                            <SelectValue />
                          </SelectTrigger>
                        </FormControl>
                        <SelectContent>
                          {SOURCE_TECHNOLOGIES.map((value) => (
                            <SelectItem key={value} value={value}>
                              {value.replace(/_/g, ' ')}
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
                  name="connectorType"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Connector type</FormLabel>
                      <Select onValueChange={field.onChange} value={field.value}>
                        <FormControl>
                          <SelectTrigger aria-label="Connector type">
                            <SelectValue />
                          </SelectTrigger>
                        </FormControl>
                        <SelectContent>
                          {CONNECTOR_TYPES.map((value) => (
                            <SelectItem key={value} value={value}>
                              {value.replace(/_/g, ' ')}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                      <FormDescription>
                        Push and file mechanisms are not fallbacks — many temples cannot permit an
                        inbound connection.
                      </FormDescription>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>

              <FormField
                control={form.control}
                name="connectorBean"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Connector bean</FormLabel>
                    <FormControl>
                      <Input {...field} placeholder="templeFinanceConnector" />
                    </FormControl>
                    <FormDescription>
                      The name the sync worker resolves the connector by. It must match a deployed
                      component; until one exists, this source cannot run.
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <div className="grid gap-4 sm:grid-cols-2">
                <FormField
                  control={form.control}
                  name="sourceTempleCode"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Temple code in the source (optional)</FormLabel>
                      <FormControl>
                        <Input {...field} placeholder="43" />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                <FormField
                  control={form.control}
                  name="sourceDatabaseName"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Source database name (optional)</FormLabel>
                      <FormControl>
                        <Input {...field} />
                      </FormControl>
                      <FormDescription>Documentation only. Never used to connect.</FormDescription>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>

              <FormField
                control={form.control}
                name="credentialRef"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Credential alias (optional)</FormLabel>
                    <FormControl>
                      <Input {...field} placeholder="temple-readonly" autoComplete="off" />
                    </FormControl>
                    <FormDescription>
                      The lookup key the sync worker resolves a credential by — never the
                      credential itself. It is stored but never shown again.
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <Alert variant="destructive">
                <ShieldAlert className="h-4 w-4" aria-hidden />
                <AlertTitle>Never enter a password here</AlertTitle>
                <AlertDescription>
                  This platform stores no passwords, hosts, ports or connection strings. The alias
                  above is a name, and the secret it names lives in the sync worker's own
                  configuration.
                </AlertDescription>
              </Alert>

              <FormField
                control={form.control}
                name="notes"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Notes (optional)</FormLabel>
                    <FormControl>
                      <Textarea {...field} rows={3} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </SheetBody>

            <SheetFooter>
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
                Cancel
              </Button>
              <Button type="submit" disabled={isLoading}>
                {isLoading && <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden />}
                Register source system
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  )
}
