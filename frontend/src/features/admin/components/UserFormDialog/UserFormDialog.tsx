import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import * as z from 'zod'
import ReactSelect from 'react-select'
import { Eye, EyeOff, Pencil, Building2, Mail } from 'lucide-react'
import { cn } from '@/lib/utils'
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter, DialogDescription,
} from '@/components/ui/dialog'
import {
  Form, FormControl, FormField, FormItem, FormLabel, FormMessage,
} from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Switch } from '@/components/ui/switch'
import { Label } from '@/components/ui/label'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import { USER_ROLES, type UserRole } from '@/constants/roles'
import { useListAllDistrictsQuery, useSearchTemplesQuery } from '../../adminApi'
import { useGetCitiesQuery, useGetTaluksQuery, useGetHoblisQuery } from '@/features/geo/geoApi'
import type { UserAdminResponse, CreateUserRequest, TempleOption } from '../../adminApi'

const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: 'Super Admin',
  DISTRICT_COLLECTOR: 'District Collector',
  DC_STAFF: 'DC Staff',
  TEMPLE_AUTHORITY: 'Temple Authority',
  AUDITOR: 'Auditor',
  VIEWER: 'Viewer',
}

const userSchema = z.object({
  role: z.enum([
    USER_ROLES.SUPER_ADMIN, USER_ROLES.DISTRICT_COLLECTOR, USER_ROLES.DC_STAFF,
    USER_ROLES.TEMPLE_AUTHORITY, USER_ROLES.AUDITOR, USER_ROLES.VIEWER,
  ] as [UserRole, ...UserRole[]]),
  username: z.string().min(3, 'At least 3 characters'),
  email: z.string().email('Invalid email'),
  password: z.string().min(8, 'At least 8 characters').optional().or(z.literal('')),
  fullName: z.string().min(2, 'Full name is required'),
  mobile: z.string().regex(/^[6-9]\d{9}$/, 'Valid 10-digit Indian mobile'),
  cityId: z.string().optional(),
  districtId: z.string().min(1, 'District is required'),
  aadhaarNumber: z.string().regex(/^\d{12}$/, 'Must be 12 digits').optional().or(z.literal('')),
  // TA: new temple
  templeName: z.string().max(255).optional(),
  // TA: new temple — geo hierarchy below district (optional)
  talukId: z.string().optional(),
  hobliId: z.string().optional(),
  // TA: existing temple — stored as string because RHF works with strings for now
  existingTempleId: z.string().optional(),
  // TA: designation and access type
  designation: z.string().max(150).optional(),
  accessType: z.enum(['VIEW', 'EDIT']).default('EDIT'),
})

type UserFormValues = z.infer<typeof userSchema>

interface UserFormDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  user?: UserAdminResponse | null
  onSubmit: (values: CreateUserRequest) => Promise<void>
  isLoading?: boolean
}

export function UserFormDialog({ open, onOpenChange, user, onSubmit, isLoading }: UserFormDialogProps) {
  const isEdit = !!user
  const [showPassword, setShowPassword] = useState(false)
  const [passwordValue, setPasswordValue] = useState('')
  const [sendCredentialsEmail, setSendCredentialsEmail] = useState(true)
  const [districtSearch, setDistrictSearch] = useState('')
  /** Toggle: true = create new temple, false = assign existing */
  const [createTemple, setCreateTemple] = useState(true)
  /** Debounced search input for the existing-temple react-select dropdown */
  const [templeSearchInput, setTempleSearchInput] = useState('')
  const [templeSearchDebounced, setTempleSearchDebounced] = useState('')
  /** The full TempleOption for the currently selected existing temple (for auto-fill) */
  const [selectedTempleOption, setSelectedTempleOption] = useState<{ value: number; label: string; subLabel: string; districtId?: number; cityId?: number } | null>(null)

  // Debounce temple search by 300 ms
  useEffect(() => {
    const t = setTimeout(() => setTempleSearchDebounced(templeSearchInput), 300)
    return () => clearTimeout(t)
  }, [templeSearchInput])

  const { data: districtsData, isLoading: loadingDistricts } = useListAllDistrictsQuery()
  const { data: citiesData, isLoading: loadingCities } = useGetCitiesQuery(1) // Karnataka stateId=1
  const districts = districtsData?.data ?? []
  const cities = citiesData?.data ?? []

  const form = useForm<UserFormValues>({
    resolver: zodResolver(userSchema),
    defaultValues: buildDefaults(user),
  })

  const watchedRoleValue = form.watch('role')
  const watchedCityId = form.watch('cityId')
  const watchedDistrictId = form.watch('districtId')
  const watchedTalukId = form.watch('talukId')
  const isTempleAuthority = watchedRoleValue === USER_ROLES.TEMPLE_AUTHORITY
  const showGeoCity = ([USER_ROLES.TEMPLE_AUTHORITY, USER_ROLES.DISTRICT_COLLECTOR, USER_ROLES.DC_STAFF] as UserRole[]).includes(watchedRoleValue as UserRole)
  // Taluk/Hobli only apply to the temple being created (Case 1) — not to DC/DC_STAFF's own jurisdiction.
  const showNewTempleGeo = isTempleAuthority && !isEdit && createTemple

  const { data: taluksData, isLoading: loadingTaluks } = useGetTaluksQuery(
    Number(watchedDistrictId), { skip: !showNewTempleGeo || !watchedDistrictId },
  )
  const taluks = taluksData?.data ?? []

  const { data: hoblisData, isLoading: loadingHoblis } = useGetHoblisQuery(
    Number(watchedTalukId), { skip: !showNewTempleGeo || !watchedTalukId },
  )
  const hoblis = hoblisData?.data ?? []

  // Temple dropdown search query (only fires when TA and toggle is OFF)
  const skipTempleSearch = !isTempleAuthority || createTemple
  const { data: templeSearchData, isLoading: loadingTemples } = useSearchTemplesQuery(
    { q: templeSearchDebounced, page: 0, size: 20 },
    { skip: skipTempleSearch },
  )
  const templeOptions: Array<{ value: number; label: string; subLabel: string; districtId?: number; cityId?: number }> =
    (templeSearchData?.data?.content ?? []).map((t: TempleOption) => ({
      value: t.id,
      label: t.name,
      subLabel: `${t.registrationNumber} · ${t.districtName ?? ''}${t.grade ? ` · Grade ${t.grade}` : ''}`,
      districtId: t.districtId,
      cityId: t.cityId,
    }))

  // Filter districts by selected city when city is chosen
  const filteredDistricts = (() => {
    let base = districts
    if (watchedCityId) {
      const cityIdNum = Number(watchedCityId)
      base = base.filter(d => (d as any).cityId === cityIdNum)
    }
    if (districtSearch.trim()) {
      base = base.filter(d => d.name.toLowerCase().includes(districtSearch.toLowerCase()))
    }
    return base
  })()

  // Reset form whenever the dialog opens or the target user changes
  useEffect(() => {
    if (open) {
      form.reset(buildDefaults(user))
      setDistrictSearch('')
      setCreateTemple(true)
      setTempleSearchInput('')
      setTempleSearchDebounced('')
      setSelectedTempleOption(null)
      setShowPassword(false)
      setPasswordValue('')
      setSendCredentialsEmail(true)
    }
  }, [open, user])

  // Clear TA-only fields when role changes away from TA
  useEffect(() => {
    if (!isTempleAuthority) {
      form.setValue('templeName', '')
      form.setValue('aadhaarNumber', '')
      form.setValue('existingTempleId', '')
      form.setValue('designation', '')
      form.setValue('talukId', '')
      form.setValue('hobliId', '')
    }
  }, [isTempleAuthority])

  // Clear taluk/hobli when the district changes — a taluk belongs to exactly one district
  useEffect(() => {
    form.setValue('talukId', '')
    form.setValue('hobliId', '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [watchedDistrictId])

  // Clear hobli when the taluk changes — a hobli belongs to exactly one taluk
  useEffect(() => {
    form.setValue('hobliId', '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [watchedTalukId])

  // Auto-fill district and city from selected existing temple (Case 2).
  // cityId is derived from the loaded districts list if the temple record doesn't carry it directly
  // (city_id is nullable on temples created before geo hierarchy was enforced).
  useEffect(() => {
    if (!createTemple && selectedTempleOption) {
      if (selectedTempleOption.districtId != null) {
        form.setValue('districtId', String(selectedTempleOption.districtId))
      }
      // Prefer direct cityId; fall back to city derived from the district record
      const resolvedCityId =
        selectedTempleOption.cityId
        ?? districts.find(d => d.id === selectedTempleOption.districtId)?.cityId
      if (resolvedCityId != null) {
        form.setValue('cityId', String(resolvedCityId))
      }
    }
  }, [selectedTempleOption, createTemple, districts])

  // Clear districtId when city changes — but skip when a temple is already selected
  // (city changed because auto-fill set it, not because the user picked a new city).
  useEffect(() => {
    if (!createTemple && selectedTempleOption != null) return
    form.setValue('districtId', '')
  }, [watchedCityId])

  const handleSubmit = async (values: UserFormValues) => {
    if (isTempleAuthority && createTemple && (!values.templeName || values.templeName.trim() === '')) {
      form.setError('templeName', { message: 'Temple name is required' })
      return
    }
    if (isTempleAuthority && !createTemple && !values.existingTempleId) {
      form.setError('existingTempleId', { message: 'Please select an existing temple' })
      return
    }
    if (isTempleAuthority && (!values.designation || values.designation.trim() === '')) {
      form.setError('designation', { message: 'Designation is required' })
      return
    }

    await onSubmit({
      username: values.username,
      email: values.email,
      password: values.password || '',
      fullName: values.fullName,
      mobile: values.mobile,
      role: values.role,
      districtId: Number(values.districtId),
      cityId: values.cityId ? Number(values.cityId) : undefined,
      aadhaarNumber: isTempleAuthority && values.aadhaarNumber ? values.aadhaarNumber : undefined,
      createTemple: isTempleAuthority ? createTemple : undefined,
      templeName: isTempleAuthority && createTemple ? values.templeName : undefined,
      talukId: isTempleAuthority && createTemple && values.talukId ? Number(values.talukId) : undefined,
      hobliId: isTempleAuthority && createTemple && values.hobliId ? Number(values.hobliId) : undefined,
      existingTempleId: isTempleAuthority && !createTemple && values.existingTempleId
        ? Number(values.existingTempleId)
        : undefined,
      designation: isTempleAuthority ? values.designation : undefined,
      accessType: isTempleAuthority ? (values.accessType ?? 'EDIT') : undefined,
      sendCredentialsEmail: sendCredentialsEmail,
    })
  }

  const selectedDistrictName = districts.find(d => String(d.id) === form.watch('districtId'))?.name

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[540px] max-h-[92vh] flex flex-col overflow-hidden p-0">
        <DialogHeader className="px-6 pt-5 pb-4 border-b shrink-0">
          <DialogTitle>{isEdit ? `Edit â€” ${user.fullName}` : 'Create New User'}</DialogTitle>
          {isEdit && (
            <DialogDescription className="text-xs">
              Username and role are locked after creation.
            </DialogDescription>
          )}
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(handleSubmit)} className="flex flex-col flex-1 overflow-hidden">
            <div className="flex-1 overflow-y-auto px-6 py-4 space-y-4">

              {/* Role */}
              <FormField control={form.control} name="role" render={({ field }) => (
                <FormItem>
                  <FormLabel>Role</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value} disabled={isEdit}>
                    <FormControl>
                      <SelectTrigger>
                        <SelectValue placeholder="Select role" />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      {Object.values(USER_ROLES).map(role => (
                        <SelectItem key={role} value={role}>{ROLE_LABELS[role] ?? role}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )} />

              {/* Create Temple toggle — shown immediately after Role when TA is selected */}
              {isTempleAuthority && !isEdit && (
                <div className="flex items-center gap-3 py-1">
                  <Switch
                    id="createTempleToggle"
                    checked={createTemple}
                    onCheckedChange={checked => {
                      setCreateTemple(checked)
                      form.setValue('templeName', '')
                      form.setValue('existingTempleId', '')
                      form.setValue('talukId', '')
                      form.setValue('hobliId', '')
                      form.clearErrors('templeName')
                      form.clearErrors('existingTempleId')
                    }}
                  />
                  <Label htmlFor="createTempleToggle" className="cursor-pointer text-sm font-medium">
                    Create Temple Also?
                  </Label>
                  <span className="text-xs text-muted-foreground">
                    {createTemple ? 'A new temple will be created' : 'Assign an existing temple'}
                  </span>
                </div>
              )}

              {/* Select Existing Temple — shown directly below the toggle when createTemple is off */}
              {isTempleAuthority && !isEdit && !createTemple && (
                <FormField control={form.control} name="existingTempleId" render={({ field }) => (
                  <FormItem>
                    <FormLabel className="flex items-center gap-1.5">
                      <Building2 size={13} className="text-muted-foreground" />
                      Select Existing Temple
                    </FormLabel>
                    <ReactSelect
                      inputId="templeSelect"
                      placeholder="Search by name, reg. no., or district..."
                      isLoading={loadingTemples}
                      isClearable
                      options={templeOptions}
                      value={selectedTempleOption ?? null}
                      onInputChange={(val) => setTempleSearchInput(val)}
                      onChange={(selected) => {
                        if (selected) {
                          field.onChange(String(selected.value))
                          setSelectedTempleOption(selected)
                        } else {
                          field.onChange('')
                          setSelectedTempleOption(null)
                        }
                      }}
                      filterOption={() => true}
                      noOptionsMessage={({ inputValue }) =>
                        inputValue.length === 0 ? 'Type to search temples' : 'No temples found'
                      }
                      loadingMessage={() => 'Searching temples...'}
                      formatOptionLabel={(opt) => (
                        <div className="py-0.5">
                          <div className="font-medium text-sm text-foreground">{opt.label}</div>
                          <div className="text-xs text-muted-foreground">{opt.subLabel}</div>
                        </div>
                      )}
                      classNames={{
                        control: (state) => cn(
                          '!min-h-9 !rounded-md !border !bg-background !text-sm !shadow-sm !transition-colors !cursor-text',
                          state.isFocused
                            ? '!border-ring !ring-2 !ring-ring/20'
                            : '!border-input hover:!border-ring/60',
                        ),
                        valueContainer: () => '!px-3 !py-0 !gap-1',
                        singleValue: () => '!text-foreground !text-sm !m-0',
                        placeholder: () => '!text-muted-foreground !text-sm !m-0',
                        input: () => '!text-sm !text-foreground !m-0 !p-0',
                        indicatorsContainer: () => '!pr-1 !gap-0',
                        clearIndicator: () => '!text-muted-foreground hover:!text-foreground !cursor-pointer !p-1.5 !rounded',
                        dropdownIndicator: () => '!text-muted-foreground hover:!text-foreground !cursor-pointer !p-1.5 !rounded',
                        indicatorSeparator: () => '!bg-border !my-2',
                        loadingIndicator: () => '!text-muted-foreground',
                        menu: () => '!z-50 !mt-1 !rounded-md !border !border-border !bg-popover !text-popover-foreground !shadow-md !overflow-hidden',
                        menuList: () => '!p-1',
                        option: (state) => cn(
                          '!rounded-sm !px-2 !py-2 !text-sm !cursor-pointer !transition-colors',
                          state.isSelected
                            ? '!bg-primary !text-primary-foreground'
                            : state.isFocused
                            ? '!bg-accent !text-accent-foreground'
                            : '!text-popover-foreground',
                        ),
                        noOptionsMessage: () => '!text-muted-foreground !text-sm !py-6 !text-center',
                        loadingMessage: () => '!text-muted-foreground !text-sm !py-6 !text-center',
                      }}
                      unstyled
                    />
                    <FormMessage />
                  </FormItem>
                )} />
              )}

              {/* City — shown for TA, DC, DC_STAFF */}
              {showGeoCity && (
                <FormField control={form.control} name="cityId" render={({ field }) => {
                  const lockedByTemple = isTempleAuthority && !createTemple && selectedTempleOption != null
                  return (
                    <FormItem>
                      <FormLabel>City / Division <span className="text-xs text-muted-foreground font-normal">(Optional)</span></FormLabel>
                      <Select onValueChange={field.onChange} value={field.value ?? ''} disabled={lockedByTemple}>
                        <FormControl>
                          <SelectTrigger>
                            <SelectValue placeholder={loadingCities ? 'Loading...' : 'Select city'} />
                          </SelectTrigger>
                        </FormControl>
                        <SelectContent className="max-h-64">
                          {cities.map(c => (
                            <SelectItem key={c.id} value={String(c.id)}>{c.name}</SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                      {lockedByTemple && (
                        <p className="text-xs text-muted-foreground">Auto-filled from selected temple</p>
                      )}
                      <FormMessage />
                    </FormItem>
                  )
                }} />
              )}

              {/* District */}
              <FormField control={form.control} name="districtId" render={({ field }) => {
                const lockedByTemple = isTempleAuthority && !createTemple && selectedTempleOption != null
                return (
                  <FormItem>
                    <FormLabel>District</FormLabel>
                    <Select
                      onValueChange={(val) => { field.onChange(val); setDistrictSearch('') }}
                      value={field.value}
                      disabled={lockedByTemple}
                    >
                      <FormControl>
                        <SelectTrigger>
                          <SelectValue placeholder={loadingDistricts ? 'Loading...' : selectedDistrictName ?? 'Select district'} />
                        </SelectTrigger>
                      </FormControl>
                      <SelectContent className="max-h-64">
                        <div className="px-2 py-1.5 border-b">
                          <Input
                            placeholder="Search district..."
                            value={districtSearch}
                            onChange={e => setDistrictSearch(e.target.value)}
                            className="h-7 text-sm"
                            onKeyDown={e => e.stopPropagation()}
                          />
                        </div>
                        {filteredDistricts.length === 0 ? (
                          <div className="py-3 text-center text-sm text-muted-foreground">No districts found</div>
                        ) : (
                          filteredDistricts.map(d => (
                            <SelectItem key={d.id} value={String(d.id)}>{d.name}</SelectItem>
                          ))
                        )}
                      </SelectContent>
                    </Select>
                    {lockedByTemple && (
                      <p className="text-xs text-muted-foreground">Auto-filled from selected temple</p>
                    )}
                    <FormMessage />
                  </FormItem>
                )
              }} />

              {/* Taluk / Hobli — new temple geo hierarchy (Case 1 only), both optional */}
              {showNewTempleGeo && (
                <div className="grid grid-cols-2 gap-4">
                  <FormField control={form.control} name="talukId" render={({ field }) => (
                    <FormItem>
                      <FormLabel>Taluk <span className="text-xs text-muted-foreground font-normal">(Optional)</span></FormLabel>
                      <Select onValueChange={field.onChange} value={field.value ?? ''} disabled={!watchedDistrictId}>
                        <FormControl>
                          <SelectTrigger>
                            <SelectValue placeholder={!watchedDistrictId ? 'Select district first' : loadingTaluks ? 'Loading...' : 'Select taluk'} />
                          </SelectTrigger>
                        </FormControl>
                        <SelectContent className="max-h-64">
                          {taluks.map(t => (
                            <SelectItem key={t.id} value={String(t.id)}>{t.name}</SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                      <FormMessage />
                    </FormItem>
                  )} />
                  <FormField control={form.control} name="hobliId" render={({ field }) => (
                    <FormItem>
                      <FormLabel>Hobli <span className="text-xs text-muted-foreground font-normal">(Optional)</span></FormLabel>
                      <Select onValueChange={field.onChange} value={field.value ?? ''} disabled={!watchedTalukId}>
                        <FormControl>
                          <SelectTrigger>
                            <SelectValue placeholder={!watchedTalukId ? 'Select taluk first' : loadingHoblis ? 'Loading...' : 'Select hobli'} />
                          </SelectTrigger>
                        </FormControl>
                        <SelectContent className="max-h-64">
                          {hoblis.map(h => (
                            <SelectItem key={h.id} value={String(h.id)}>{h.name}</SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                      <FormMessage />
                    </FormItem>
                  )} />
                </div>
              )}

              {/* Username + Email */}
              <div className="grid grid-cols-2 gap-4">
                <FormField control={form.control} name="username" render={({ field }) => (
                  <FormItem>
                    <FormLabel>Username</FormLabel>
                    <FormControl><Input {...field} disabled={isEdit} placeholder="jdoe" /></FormControl>
                    <FormMessage />
                  </FormItem>
                )} />
                <FormField control={form.control} name="email" render={({ field }) => (
                  <FormItem>
                    <FormLabel>Email</FormLabel>
                    <FormControl><Input {...field} type="email" placeholder="john@example.com" /></FormControl>
                    <FormMessage />
                  </FormItem>
                )} />
              </div>

              {/* Password — create only */}
              {!isEdit && (
                <FormField control={form.control} name="password" render={({ field }) => {
                  const score = getPasswordStrength(passwordValue)
                  const colors = ['bg-red-500', 'bg-orange-400', 'bg-yellow-400', 'bg-emerald-500']
                  const labels = ['Weak', 'Fair', 'Good', 'Strong']
                  return (
                    <FormItem>
                      <FormLabel>Password</FormLabel>
                      <FormControl>
                        <div className="relative">
                          <Input
                            {...field}
                            type={showPassword ? 'text' : 'password'}
                            placeholder="Min 8 characters"
                            className="pr-10"
                            onChange={e => { field.onChange(e); setPasswordValue(e.target.value) }}
                          />
                          <button
                            type="button"
                            tabIndex={-1}
                            onClick={() => setShowPassword(p => !p)}
                            className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                          >
                            {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                          </button>
                        </div>
                      </FormControl>
                      {passwordValue.length > 0 && (
                        <div className="space-y-1 pt-1">
                          <div className="flex gap-1 h-1.5">
                            {[0, 1, 2, 3].map(i => (
                              <div key={i} className={`flex-1 rounded-full transition-colors ${i < score ? colors[score - 1] : 'bg-muted'}`} />
                            ))}
                          </div>
                          <p className={`text-xs font-medium ${score >= 3 ? 'text-emerald-600' : score === 2 ? 'text-yellow-600' : 'text-red-500'}`}>
                            {labels[score - 1] ?? 'Too short'}
                          </p>
                        </div>
                      )}
                      <FormMessage />
                    </FormItem>
                  )
                }} />
              )}

              {/* Full Name */}
              <FormField control={form.control} name="fullName" render={({ field }) => (
                <FormItem>
                  <FormLabel>Full Name</FormLabel>
                  <FormControl><Input {...field} placeholder="John Doe" /></FormControl>
                  <FormMessage />
                </FormItem>
              )} />

              {/* Mobile */}
              <FormField control={form.control} name="mobile" render={({ field }) => (
                <FormItem>
                  <FormLabel>Mobile</FormLabel>
                  <FormControl><Input {...field} placeholder="9876543210" inputMode="numeric" /></FormControl>
                  <FormMessage />
                </FormItem>
              )} />

              {/* TA-only fields */}
              {isTempleAuthority && !isEdit && (
                <div className="space-y-4 rounded-md border p-4 bg-muted/30">
                  {/* Aadhaar */}
                  <FormField control={form.control} name="aadhaarNumber" render={({ field }) => (
                    <FormItem>
                      <FormLabel>Aadhaar Number</FormLabel>
                      <FormControl>
                        <Input {...field} placeholder="123456789012" inputMode="numeric" maxLength={12}
                          onChange={e => field.onChange(e.target.value.replace(/\D/g, '').slice(0, 12))} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )} />

                  {/* Case 1: New temple name */}
                  {createTemple && (
                    <FormField control={form.control} name="templeName" render={({ field }) => (
                      <FormItem>
                        <FormLabel>Temple Name</FormLabel>
                        <FormControl>
                          <Input {...field} placeholder="Sri Chamundeshwari Temple" />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )} />
                  )}

                </div>
              )}

              {/* TA-only: Designation + Access Type (shown in both create and edit) */}
              {isTempleAuthority && (
                <>
                  <FormField control={form.control} name="designation" render={({ field }) => (
                    <FormItem>
                      <FormLabel>Designation</FormLabel>
                      <FormControl>
                        <Input {...field} placeholder="e.g. Trust Secretary, Archaka, Trustee" />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )} />

                  <FormField control={form.control} name="accessType" render={({ field }) => (
                    <FormItem>
                      <FormLabel>Access Type</FormLabel>
                      <Select onValueChange={field.onChange} value={field.value ?? 'EDIT'}>
                        <FormControl>
                          <SelectTrigger>
                            <SelectValue>
                              {field.value === 'VIEW'
                                ? <span className="flex items-center gap-1.5"><Eye size={13} className="text-muted-foreground" />View Only</span>
                                : <span className="flex items-center gap-1.5"><Pencil size={13} className="text-primary" />Edit Access</span>
                              }
                            </SelectValue>
                          </SelectTrigger>
                        </FormControl>
                        <SelectContent>
                          <SelectItem value="EDIT">
                            <div className="flex items-start gap-2.5 py-0.5">
                              <Pencil size={13} className="text-primary mt-0.5 shrink-0" />
                              <div>
                                <div className="font-medium text-sm">Edit Access</div>
                                <div className="text-xs text-muted-foreground">Submit, upload &amp; manage temple data</div>
                              </div>
                            </div>
                          </SelectItem>
                          <SelectItem value="VIEW">
                            <div className="flex items-start gap-2.5 py-0.5">
                              <Eye size={13} className="text-muted-foreground mt-0.5 shrink-0" />
                              <div>
                                <div className="font-medium text-sm">View Only</div>
                                <div className="text-xs text-muted-foreground">Read-only access, no changes allowed</div>
                              </div>
                            </div>
                          </SelectItem>
                        </SelectContent>
                      </Select>
                      <FormMessage />
                    </FormItem>
                  )} />
                </>
              )}

            </div>

            {/* Send Credentials toggle — create only, only shown when a password is entered */}
            {!isEdit && passwordValue.length >= 8 && (
              <div className="px-6 pb-3">
                <div
                  role="button"
                  tabIndex={0}
                  onClick={() => setSendCredentialsEmail(v => !v)}
                  onKeyDown={(e) => { if (e.key === ' ' || e.key === 'Enter') setSendCredentialsEmail(v => !v) }}
                  className={cn(
                    'flex items-start gap-3 rounded-lg border px-4 py-3 cursor-pointer select-none transition-all duration-150',
                    sendCredentialsEmail
                      ? 'border-primary/50 bg-primary/5 ring-1 ring-primary/20'
                      : 'border-border hover:border-border/80 hover:bg-muted/30',
                  )}
                  aria-pressed={sendCredentialsEmail}
                >
                  <div className="mt-0.5">
                    <Switch
                      id="sendCredentialsSwitch"
                      checked={sendCredentialsEmail}
                      onCheckedChange={setSendCredentialsEmail}
                      onClick={(e) => e.stopPropagation()}
                      aria-label="Send login credentials via email"
                    />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-1.5">
                      <Mail size={13} className={cn('shrink-0', sendCredentialsEmail ? 'text-primary' : 'text-muted-foreground')} />
                      <span className={cn('text-sm font-medium', sendCredentialsEmail ? 'text-foreground' : 'text-muted-foreground')}>
                        Send Credentials via Email
                      </span>
                    </div>
                    <p className="text-xs text-muted-foreground mt-0.5">
                      {sendCredentialsEmail
                        ? 'An account-created email with username & password will be sent to the user.'
                        : "Enable to send the username and temporary password to the user's email address."}
                    </p>
                  </div>
                </div>
              </div>
            )}

            <DialogFooter className="px-6 py-4 border-t shrink-0">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={isLoading}>
                {isLoading ? 'Saving...' : isEdit ? 'Save Changes' : 'Create User'}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  )
}

function getPasswordStrength(password: string): number {
  if (password.length < 8) return 0
  let score = 1
  if (/[A-Z]/.test(password)) score++
  if (/[0-9]/.test(password)) score++
  if (/[^A-Za-z0-9]/.test(password)) score++
  return score
}

function buildDefaults(user?: UserAdminResponse | null): UserFormValues {
  return {
    role: user?.role ?? USER_ROLES.DISTRICT_COLLECTOR,
    username: user?.username ?? '',
    email: user?.email ?? '',
    password: '',
    fullName: user?.fullName ?? '',
    mobile: user?.mobile ?? '',
    cityId: user?.cityId != null ? String(user.cityId) : '',
    districtId: user?.districtId != null ? String(user.districtId) : '',
    templeName: '',
    talukId: '',
    hobliId: '',
    aadhaarNumber: user?.aadhaarNumber ?? '',
    existingTempleId: '',
    designation: user?.designation ?? '',
    accessType: (user?.accessType as 'VIEW' | 'EDIT') ?? 'EDIT',
  }
}
