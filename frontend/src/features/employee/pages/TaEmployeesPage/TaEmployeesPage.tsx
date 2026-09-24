import { useState, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { toast } from 'sonner'
import { extractApiErrorMessage } from '@/lib/apiError'
import { useGetCurrentUserQuery } from '@/features/auth/authApi'
import {
  useListEmployeesQuery, useCreateEmployeeMutation,
  useUpdateEmployeeMutation, useDeleteEmployeeMutation,
} from '@/features/employee/employeeApi'
import {
  createEmployeeSchema, updateEmployeeSchema,
  EMPLOYEE_TYPES, EMPLOYEE_STATUSES, TERMINAL_EMPLOYEE_STATUSES,
  type CreateEmployeeRequest, type UpdateEmployeeRequest, type EmployeeResponse,
} from '@/features/employee/employeeTypes'
import { StatusBadge } from '@/components/data-display/StatusBadge/StatusBadge'
import { CardSkeleton, TableBodySkeleton } from '@/components/feedback/Skeleton/Skeleton'
import { EmptyState } from '@/components/feedback/EmptyState/EmptyState'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle, AlertDialogTrigger,
} from '@/components/ui/alert-dialog'
import {
  Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle,
} from '@/components/ui/dialog'
import { Switch } from '@/components/ui/switch'
import { DEFAULT_PAGE_SIZE } from '@/constants/pagination'
import { Eye, Plus, Pencil, Trash2, Users, Search, X } from 'lucide-react'
import { Card, CardContent } from '@/components/ui/card'

type FormMode = 'create' | 'edit' | null

export function TaEmployeesPage() {
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const [mode, setMode] = useState<FormMode>(null)
  const [selectedEmployee, setSelectedEmployee] = useState<EmployeeResponse | null>(null)
  const [searchQuery, setSearchQuery] = useState('')

  const { data: userData } = useGetCurrentUserQuery()
  const templeId = userData?.data?.templeId
  const isViewOnly = userData?.data?.accessType === 'VIEW'

  const { data, isLoading, isError, refetch } = useListEmployeesQuery(
    { templeId: templeId!, page, size: DEFAULT_PAGE_SIZE },
    { skip: !templeId, refetchOnMountOrArgChange: true }
  )

  const [createEmployee, { isLoading: creating }] = useCreateEmployeeMutation()
  const [updateEmployee, { isLoading: updating }] = useUpdateEmployeeMutation()
  const [deleteEmployee, { isLoading: deleting }] = useDeleteEmployeeMutation()

  const employees = data?.data?.content ?? []
  const totalPages = data?.data?.totalPages ?? 0
  const totalElements = data?.data?.totalElements ?? 0

  const filteredEmployees = useMemo(() => {
    if (!searchQuery.trim()) return employees
    const q = searchQuery.toLowerCase()
    return employees.filter(
      (emp) =>
        emp.fullName.toLowerCase().includes(q) ||
        (emp.designation ?? '').toLowerCase().includes(q) ||
        (emp.employeeRef ?? '').toLowerCase().includes(q)
    )
  }, [employees, searchQuery])

  const createForm = useForm<CreateEmployeeRequest>({
    resolver: zodResolver(createEmployeeSchema),
    defaultValues: { fullName: '', isHereditary: false },
  })

  const updateForm = useForm<UpdateEmployeeRequest>({
    resolver: zodResolver(updateEmployeeSchema),
  })

  const openEdit = (emp: EmployeeResponse) => {
    setSelectedEmployee(emp)
    updateForm.reset({
      fullName: emp.fullName,
      employeeType: emp.employeeType,
      designation: emp.designation ?? '',
      salaryGrade: emp.salaryGrade ?? '',
      mobile: emp.mobile ?? '',
      address: emp.address ?? '',
      status: emp.status,
    })
    setMode('edit')
  }

  const closeForm = () => {
    setMode(null)
    setSelectedEmployee(null)
    createForm.reset()
    updateForm.reset()
  }

  const onCreateSubmit = async (values: CreateEmployeeRequest) => {
    if (!templeId) return
    try {
      await createEmployee({ templeId, body: values }).unwrap()
      toast.success('Employee added successfully')
      closeForm()
    } catch (err) {
      toast.error(extractApiErrorMessage(err, 'Failed to add employee'))
    }
  }

  const onUpdateSubmit = async (values: UpdateEmployeeRequest) => {
    if (!selectedEmployee) return
    try {
      await updateEmployee({ id: selectedEmployee.id, body: values }).unwrap()
      toast.success('Employee updated successfully')
      closeForm()
    } catch (err) {
      toast.error(extractApiErrorMessage(err, 'Failed to update employee'))
    }
  }

  const onDelete = async (id: number) => {
    try {
      await deleteEmployee(id).unwrap()
      toast.success('Employee removed successfully')
    } catch (err) {
      toast.error(extractApiErrorMessage(err, 'Failed to remove employee'))
    }
  }

  const watchedStatus = updateForm.watch('status')

  const getEmployeeTypeLabel = (type: string) => {
    const labels: Record<string, string> = {
      PRIEST: 'Priest (Archaka)',
      ADMINISTRATIVE: 'Administrative',
      MAINTENANCE: 'Maintenance',
      SECURITY: 'Security',
      OTHER: 'Other',
    }
    return labels[type] || type
  }

  if (isError) {
    return (
      <EmptyState
        title="Failed to load employees"
        description="Unable to fetch employee data. Please try again."
        action={{ label: 'Retry', onClick: () => refetch() }}
      />
    )
  }

  return (
    <div className="space-y-5">
      <Card className="overflow-hidden border-border/60 bg-gradient-to-br from-primary/5 via-card to-secondary/5 shadow-sm">
        <CardContent className="p-5">
          <div className="flex items-start justify-between gap-4">
            <div className="flex items-start gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-primary/20 to-primary/10">
                <Users size={20} className="text-primary" />
              </div>
              <div>
                <h1 className="text-2xl font-semibold tracking-tight text-foreground">Employees</h1>
                <p className="mt-0.5 text-xs text-muted-foreground">
                  Manage temple employee records · {totalElements} total
                </p>
              </div>
            </div>
            {!isViewOnly && (
              <Button className="bg-gradient-gold shadow-gold" onClick={() => setMode('create')}>
                <Plus className="size-4 mr-2" />
                Add Employee
              </Button>
            )}
          </div>
        </CardContent>
      </Card>

      {/* Create Dialog */}
      <Dialog open={mode === 'create'} onOpenChange={(open) => !open && closeForm()}>
        <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>Add New Employee</DialogTitle>
            <DialogDescription>
              Fill in the employee details. Employee ID will be auto-generated.
            </DialogDescription>
          </DialogHeader>
          <Form {...createForm}>
            <form onSubmit={createForm.handleSubmit(onCreateSubmit)} className="space-y-4 mt-4">
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <FormField control={createForm.control} name="fullName" render={({ field }) => (
                  <FormItem className="sm:col-span-2">
                    <FormLabel>Full Name *</FormLabel>
                    <FormControl><Input {...field} placeholder="Enter full legal name" /></FormControl>
                    <FormMessage />
                  </FormItem>
                )} />
                
                <FormField control={createForm.control} name="employeeType" render={({ field }) => (
                  <FormItem>
                    <FormLabel>Employee Type *</FormLabel>
                    <Select onValueChange={field.onChange} defaultValue={field.value}>
                      <FormControl><SelectTrigger><SelectValue placeholder="Select type" /></SelectTrigger></FormControl>
                      <SelectContent>
                        <SelectItem value="PRIEST">Priest (Archaka)</SelectItem>
                        <SelectItem value="ADMINISTRATIVE">Administrative Staff</SelectItem>
                        <SelectItem value="MAINTENANCE">Maintenance</SelectItem>
                        <SelectItem value="SECURITY">Security</SelectItem>
                        <SelectItem value="OTHER">Other</SelectItem>
                      </SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )} />

                <FormField control={createForm.control} name="designation" render={({ field }) => (
                  <FormItem>
                    <FormLabel>Designation</FormLabel>
                    <FormControl><Input {...field} placeholder="Role title" /></FormControl>
                    <FormMessage />
                  </FormItem>
                )} />

                <FormField control={createForm.control} name="dateOfJoining" render={({ field }) => (
                  <FormItem>
                    <FormLabel>Date of Joining</FormLabel>
                    <FormControl><Input type="date" {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )} />

                <FormField control={createForm.control} name="salaryGrade" render={({ field }) => (
                  <FormItem>
                    <FormLabel>Salary Grade</FormLabel>
                    <FormControl><Input {...field} placeholder="Pay grade classification" /></FormControl>
                    <FormMessage />
                  </FormItem>
                )} />

                <FormField control={createForm.control} name="mobile" render={({ field }) => (
                  <FormItem>
                    <FormLabel>Mobile</FormLabel>
                    <FormControl><Input {...field} placeholder="Contact number" /></FormControl>
                    <FormMessage />
                  </FormItem>
                )} />

                <FormField control={createForm.control} name="address" render={({ field }) => (
                  <FormItem className="sm:col-span-2">
                    <FormLabel>Address</FormLabel>
                    <FormControl><Textarea {...field} placeholder="Residential address" rows={2} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )} />

                <FormField control={createForm.control} name="isHereditary" render={({ field }) => (
                  <FormItem className="flex flex-row items-center justify-between rounded-lg border border-border p-4 sm:col-span-2">
                    <div className="space-y-0.5">
                      <FormLabel className="text-base">Hereditary Role</FormLabel>
                      <p className="text-sm text-muted-foreground">Mark if this is a hereditary position</p>
                    </div>
                    <FormControl>
                      <Switch checked={field.value} onCheckedChange={field.onChange} />
                    </FormControl>
                  </FormItem>
                )} />
              </div>

              <div className="flex gap-3 pt-4">
                <Button type="submit" className="bg-gradient-gold shadow-gold" disabled={creating}>
                  {creating ? 'Adding…' : 'Add Employee'}
                </Button>
                <Button type="button" variant="outline" onClick={closeForm}>Cancel</Button>
              </div>
            </form>
          </Form>
        </DialogContent>
      </Dialog>

      {/* Edit Dialog */}
      <Dialog open={mode === 'edit'} onOpenChange={(open) => !open && closeForm()}>
        <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>Edit Employee</DialogTitle>
            <DialogDescription>
              Update employee information for {selectedEmployee?.fullName}
            </DialogDescription>
          </DialogHeader>
          <Form {...updateForm}>
            <form onSubmit={updateForm.handleSubmit(onUpdateSubmit)} className="space-y-4 mt-4">
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <FormField control={updateForm.control} name="fullName" render={({ field }) => (
                  <FormItem className="sm:col-span-2">
                    <FormLabel>Full Name</FormLabel>
                    <FormControl><Input {...field} placeholder="Enter full legal name" /></FormControl>
                    <FormMessage />
                  </FormItem>
                )} />
                
                <FormField control={updateForm.control} name="employeeType" render={({ field }) => (
                  <FormItem>
                    <FormLabel>Employee Type</FormLabel>
                    <Select onValueChange={field.onChange} defaultValue={field.value}>
                      <FormControl><SelectTrigger><SelectValue placeholder="Select type" /></SelectTrigger></FormControl>
                      <SelectContent>
                        <SelectItem value="PRIEST">Priest (Archaka)</SelectItem>
                        <SelectItem value="ADMINISTRATIVE">Administrative Staff</SelectItem>
                        <SelectItem value="MAINTENANCE">Maintenance</SelectItem>
                        <SelectItem value="SECURITY">Security</SelectItem>
                        <SelectItem value="OTHER">Other</SelectItem>
                      </SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )} />

                <FormField control={updateForm.control} name="designation" render={({ field }) => (
                  <FormItem>
                    <FormLabel>Designation</FormLabel>
                    <FormControl><Input {...field} placeholder="Role title" /></FormControl>
                    <FormMessage />
                  </FormItem>
                )} />

                <FormField control={updateForm.control} name="salaryGrade" render={({ field }) => (
                  <FormItem>
                    <FormLabel>Salary Grade</FormLabel>
                    <FormControl><Input {...field} placeholder="Pay grade classification" /></FormControl>
                    <FormMessage />
                  </FormItem>
                )} />

                <FormField control={updateForm.control} name="status" render={({ field }) => (
                  <FormItem>
                    <FormLabel>Status</FormLabel>
                    <Select onValueChange={field.onChange} defaultValue={field.value}>
                      <FormControl><SelectTrigger><SelectValue placeholder="Select status" /></SelectTrigger></FormControl>
                      <SelectContent>
                        <SelectItem value="ACTIVE">Active</SelectItem>
                        <SelectItem value="ON_LEAVE">On Leave</SelectItem>
                        <SelectItem value="RETIRED">Retired</SelectItem>
                        <SelectItem value="RESIGNED">Resigned</SelectItem>
                      </SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )} />

                {watchedStatus && TERMINAL_EMPLOYEE_STATUSES.includes(watchedStatus as typeof TERMINAL_EMPLOYEE_STATUSES[number]) && (
                  <FormField control={updateForm.control} name="dateOfLeaving" render={({ field }) => (
                    <FormItem>
                      <FormLabel>Date of Leaving *</FormLabel>
                      <FormControl><Input type="date" {...field} /></FormControl>
                      <FormMessage />
                    </FormItem>
                  )} />
                )}

                <FormField control={updateForm.control} name="mobile" render={({ field }) => (
                  <FormItem>
                    <FormLabel>Mobile</FormLabel>
                    <FormControl><Input {...field} placeholder="Contact number" /></FormControl>
                    <FormMessage />
                  </FormItem>
                )} />

                <FormField control={updateForm.control} name="address" render={({ field }) => (
                  <FormItem className="sm:col-span-2">
                    <FormLabel>Address</FormLabel>
                    <FormControl><Textarea {...field} placeholder="Residential address" rows={2} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )} />
              </div>

              <div className="flex gap-3 pt-4">
                <Button type="submit" className="bg-gradient-gold shadow-gold" disabled={updating}>
                  {updating ? 'Saving…' : 'Save Changes'}
                </Button>
                <Button type="button" variant="outline" onClick={closeForm}>Cancel</Button>
              </div>
            </form>
          </Form>
        </DialogContent>
      </Dialog>

      {/* Search + Table */}
      {!templeId || isLoading ? (
        <TableBodySkeleton rows={6} cols={7} />
      ) : employees.length === 0 ? (
        <EmptyState
          title="No employees yet"
          description="Add your first employee record to get started."
          action={!isViewOnly ? { label: '+ Add Employee', onClick: () => setMode('create') } : undefined}
        />
      ) : (
        <div className="rounded-lg border border-border overflow-hidden bg-card shadow-sm">
          {/* Search bar */}
          <div className="px-4 py-3 border-b border-border bg-muted/20 flex items-center gap-2">
            <Search className="size-4 text-muted-foreground shrink-0" />
            <Input
              value={searchQuery}
              onChange={(e) => { setSearchQuery(e.target.value); setPage(0) }}
              placeholder="Search by name, designation or employee ID…"
              className="h-8 border-0 bg-transparent shadow-none focus-visible:ring-0 text-sm"
            />
            {searchQuery && (
              <button
                onClick={() => setSearchQuery('')}
                className="text-muted-foreground hover:text-foreground transition-colors"
                aria-label="Clear search"
              >
                <X className="size-4" />
              </button>
            )}
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-muted/40 border-b border-border">
                <tr>
                  <th className="px-4 py-3 text-left font-semibold text-muted-foreground">Employee ID</th>
                  <th className="px-4 py-3 text-left font-semibold text-muted-foreground">Name</th>
                  <th className="px-4 py-3 text-left font-semibold text-muted-foreground">Type</th>
                  <th className="px-4 py-3 text-left font-semibold text-muted-foreground">Designation</th>
                  <th className="px-4 py-3 text-left font-semibold text-muted-foreground">Mobile</th>
                  <th className="px-4 py-3 text-left font-semibold text-muted-foreground">Status</th>
                  <th className="px-4 py-3 text-center font-semibold text-muted-foreground">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {filteredEmployees.length === 0 ? (
                  <tr>
                    <td colSpan={7} className="px-4 py-10 text-center text-sm text-muted-foreground">
                      No employees match "{searchQuery}"
                    </td>
                  </tr>
                ) : filteredEmployees.map((emp) => (
                  <tr key={emp.id} className="hover:bg-muted/30 transition-colors">
                    <td className="px-4 py-3">
                      <span className="font-mono text-xs bg-muted/50 px-2 py-1 rounded">
                        {emp.employeeRef ?? `EMP-${emp.id}`}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <div className="font-medium">{emp.fullName}</div>
                      {emp.isHereditary && (
                        <span className="text-xs text-amber-600 flex items-center gap-1 mt-0.5">
                          Hereditary
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">
                      {getEmployeeTypeLabel(emp.employeeType)}
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">{emp.designation ?? '—'}</td>
                    <td className="px-4 py-3 text-muted-foreground">{emp.mobile ?? '—'}</td>
                    <td className="px-4 py-3"><StatusBadge status={emp.status} /></td>
                    <td className="px-4 py-3">
                      <div className="flex justify-center gap-1">
                        <Button 
                          variant="ghost" 
                          size="sm" 
                          onClick={() => navigate(`/ta/employees/${emp.id}`)}
                          className="h-8 w-8 p-0"
                        >
                          <Eye className="size-4" />
                        </Button>
                        {!isViewOnly && (
                          <Button 
                            variant="ghost" 
                            size="sm" 
                            onClick={() => openEdit(emp)}
                            className="h-8 w-8 p-0"
                          >
                            <Pencil className="size-4" />
                          </Button>
                        )}
                        {!isViewOnly && (
                          <AlertDialog>
                            <AlertDialogTrigger asChild>
                              <Button 
                                variant="ghost" 
                                size="sm" 
                                className="h-8 w-8 p-0 text-destructive hover:text-destructive"
                              >
                                <Trash2 className="size-4" />
                              </Button>
                            </AlertDialogTrigger>
                            <AlertDialogContent>
                              <AlertDialogHeader>
                                <AlertDialogTitle>Remove {emp.fullName}?</AlertDialogTitle>
                                <AlertDialogDescription>
                                  This action marks the employee record as deleted. It cannot be undone.
                                </AlertDialogDescription>
                              </AlertDialogHeader>
                              <AlertDialogFooter>
                                <AlertDialogCancel>Cancel</AlertDialogCancel>
                                <AlertDialogAction 
                                  className="bg-destructive text-destructive-foreground hover:bg-destructive/90" 
                                  onClick={() => onDelete(emp.id)} 
                                  disabled={deleting}
                                >
                                  Remove
                                </AlertDialogAction>
                              </AlertDialogFooter>
                            </AlertDialogContent>
                          </AlertDialog>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          
          {/* Pagination */}
          {totalPages > 1 && (
            <div className="flex items-center justify-between px-4 py-3 border-t border-border bg-muted/20">
              <div className="text-sm text-muted-foreground">
                Showing {page * DEFAULT_PAGE_SIZE + 1} to {Math.min((page + 1) * DEFAULT_PAGE_SIZE, totalElements)} of {totalElements} employees
              </div>
              <div className="flex gap-2">
                <Button 
                  variant="outline" 
                  size="sm" 
                  disabled={page === 0} 
                  onClick={() => setPage(p => p - 1)}
                >
                  Previous
                </Button>
                <div className="flex items-center gap-1">
                  {Array.from({ length: Math.min(5, totalPages) }, (_, i) => {
                    const pageNum = page < 3 ? i : page - 2 + i
                    if (pageNum >= totalPages) return null
                    return (
                      <Button
                        key={pageNum}
                        variant={pageNum === page ? 'default' : 'outline'}
                        size="sm"
                        onClick={() => setPage(pageNum)}
                        className="w-8 h-8 p-0"
                      >
                        {pageNum + 1}
                      </Button>
                    )
                  })}
                </div>
                <Button 
                  variant="outline" 
                  size="sm" 
                  disabled={page >= totalPages - 1} 
                  onClick={() => setPage(p => p + 1)}
                >
                  Next
                </Button>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
