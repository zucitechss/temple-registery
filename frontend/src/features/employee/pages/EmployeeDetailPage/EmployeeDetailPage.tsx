import { useParams, useNavigate } from 'react-router-dom'
import { useGetEmployeeByIdQuery } from '@/features/employee/employeeApi'
import { StatusBadge } from '@/components/data-display/StatusBadge/StatusBadge'
import { CardSkeleton, TableBodySkeleton } from '@/components/feedback/Skeleton/Skeleton'
import { EmptyState } from '@/components/feedback/EmptyState/EmptyState'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Separator } from '@/components/ui/separator'
import { ArrowLeft, User, Briefcase, Phone, MapPin, Calendar, Shield, Award, Hash, Clock, UserCheck } from 'lucide-react'

export function EmployeeDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { data, isLoading, isError } = useGetEmployeeByIdQuery(Number(id), {
    skip: !id,
    refetchOnMountOrArgChange: true,
  })

  const employee = data?.data

  if (isLoading) {
    return (
      <div className="space-y-4 max-w-6xl mx-auto pb-8">
        <CardSkeleton />
        <TableBodySkeleton rows={5} cols={2} />
      </div>
    )
  }

  if (isError || !employee) {
    return (
      <EmptyState
        title="Employee not found"
        description="The employee record you're looking for doesn't exist."
        action={{ label: 'Go Back', onClick: () => navigate(-1) }}
      />
    )
  }

  const getEmployeeTypeLabel = (type: string) => {
    const labels: Record<string, string> = {
      PRIEST: 'Priest (Archaka)',
      ADMINISTRATIVE: 'Administrative Staff',
      MAINTENANCE: 'Maintenance',
      SECURITY: 'Security',
      OTHER: 'Other',
    }
    return labels[type] || type
  }

  return (
    <div className="space-y-4 max-w-6xl mx-auto pb-8">
      {/* Header */}
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="icon" onClick={() => navigate(-1)} className="shrink-0">
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <div className="flex-1">
          <h1 className="text-xl font-bold text-foreground">Employee Profile</h1>
          <p className="text-xs text-muted-foreground mt-0.5">Complete employee information and details</p>
        </div>
      </div>

      {/* Profile Header Card */}
      <Card className="border-border shadow-sm overflow-hidden">
        <div className="bg-gradient-to-r from-primary/10 via-primary/5 to-transparent p-4">
          <div className="flex items-start gap-4">
            <div className="size-16 rounded-full bg-gradient-to-br from-primary to-primary/60 flex items-center justify-center border-2 border-white shadow-md">
              <User className="size-8 text-white" />
            </div>
            <div className="flex-1">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <h2 className="text-xl font-bold text-foreground">{employee.fullName}</h2>
                  <div className="flex items-center gap-2 mt-1">
                    <span className="text-sm text-muted-foreground">{getEmployeeTypeLabel(employee.employeeType)}</span>
                    {employee.designation && (
                      <>
                        <span className="text-muted-foreground text-xs">•</span>
                        <span className="text-sm text-muted-foreground">{employee.designation}</span>
                      </>
                    )}
                  </div>
                  {employee.employeeRef && (
                    <div className="flex items-center gap-1.5 mt-2">
                      <Hash className="size-3 text-muted-foreground" />
                      <span className="font-mono text-xs bg-muted px-2 py-0.5 rounded">{employee.employeeRef}</span>
                    </div>
                  )}
                </div>
                <div className="flex flex-col items-end gap-1.5">
                  <StatusBadge status={employee.status} />
                  {employee.isHereditary && (
                    <div className="inline-flex items-center gap-1.5 px-2 py-1 rounded-full bg-amber-100 text-amber-800 text-xs font-medium border border-amber-200">
                      <Shield className="size-3" />
                      Hereditary
                    </div>
                  )}
                </div>
              </div>
            </div>
          </div>
        </div>
      </Card>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {/* Left Column - Main Details */}
        <div className="lg:col-span-2 space-y-4">
          {/* Employment Information */}
          <Card className="border-border shadow-sm">
            <CardHeader className="pb-2">
              <CardTitle className="text-base flex items-center gap-2">
                <Briefcase className="size-4 text-primary" />
                Employment Information
              </CardTitle>
            </CardHeader>
            <Separator />
            <CardContent className="pt-4">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {/* Employee ID */}
                <div className="space-y-1">
                  <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground uppercase tracking-wide">
                    <Hash className="size-3" />
                    Employee ID
                  </div>
                  <p className="text-sm font-mono bg-muted/50 px-2 py-1.5 rounded border border-border">
                    {employee.employeeRef || `EMP-${employee.id}`}
                  </p>
                </div>

                {/* Employee Type */}
                <div className="space-y-1">
                  <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground uppercase tracking-wide">
                    <UserCheck className="size-3" />
                    Employee Type
                  </div>
                  <p className="text-sm font-semibold text-foreground">
                    {getEmployeeTypeLabel(employee.employeeType)}
                  </p>
                </div>

                {/* Designation */}
                <div className="space-y-1">
                  <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground uppercase tracking-wide">
                    <Briefcase className="size-3" />
                    Designation
                  </div>
                  <p className="text-sm font-semibold text-foreground">
                    {employee.designation || '—'}
                  </p>
                </div>

                {/* Salary Grade */}
                <div className="space-y-1">
                  <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground uppercase tracking-wide">
                    <Award className="size-3" />
                    Salary Grade
                  </div>
                  <p className="text-sm font-semibold text-foreground">
                    {employee.salaryGrade || '—'}
                  </p>
                </div>

                {/* Date of Joining */}
                <div className="space-y-1">
                  <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground uppercase tracking-wide">
                    <Calendar className="size-3" />
                    Date of Joining
                  </div>
                  <p className="text-sm font-semibold text-foreground">
                    {employee.dateOfJoining 
                      ? new Date(employee.dateOfJoining).toLocaleDateString('en-IN', { 
                          day: '2-digit', 
                          month: 'short', 
                          year: 'numeric' 
                        })
                      : '—'}
                  </p>
                </div>

                {/* Date of Leaving */}
                {employee.dateOfLeaving && (
                  <div className="space-y-1">
                    <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground uppercase tracking-wide">
                      <Calendar className="size-3" />
                      Date of Leaving
                    </div>
                    <p className="text-sm font-semibold text-destructive">
                      {new Date(employee.dateOfLeaving).toLocaleDateString('en-IN', { 
                        day: '2-digit', 
                        month: 'short', 
                        year: 'numeric' 
                      })}
                    </p>
                  </div>
                )}

                {/* Employment Status */}
                <div className="space-y-1">
                  <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground uppercase tracking-wide">
                    <UserCheck className="size-3" />
                    Employment Status
                  </div>
                  <div>
                    <StatusBadge status={employee.status} />
                  </div>
                </div>

                {/* Hereditary Role */}
                <div className="space-y-1">
                  <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground uppercase tracking-wide">
                    <Shield className="size-3" />
                    Hereditary Role
                  </div>
                  <p className="text-sm font-semibold text-foreground">
                    {employee.isHereditary ? (
                      <span className="text-amber-600 flex items-center gap-1">
                        <Shield className="size-3.5" />
                        Yes
                      </span>
                    ) : (
                      <span className="text-muted-foreground">No</span>
                    )}
                  </p>
                </div>
              </div>
            </CardContent>
          </Card>

          {/* Contact Information */}
          <Card className="border-border shadow-sm">
            <CardHeader className="pb-2">
              <CardTitle className="text-base flex items-center gap-2">
                <Phone className="size-4 text-primary" />
                Contact Information
              </CardTitle>
            </CardHeader>
            <Separator />
            <CardContent className="pt-4">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {/* Mobile */}
                <div className="space-y-1">
                  <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground uppercase tracking-wide">
                    <Phone className="size-3" />
                    Mobile Number
                  </div>
                  <p className="text-sm font-semibold text-foreground">
                    {employee.mobile || (
                      <span className="text-muted-foreground italic font-normal">Not provided</span>
                    )}
                  </p>
                </div>

                {/* Address */}
                <div className="space-y-1 md:col-span-2">
                  <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground uppercase tracking-wide">
                    <MapPin className="size-3" />
                    Residential Address
                  </div>
                  <p className="text-sm font-semibold text-foreground">
                    {employee.address || (
                      <span className="text-muted-foreground italic font-normal">Not provided</span>
                    )}
                  </p>
                </div>
              </div>
            </CardContent>
          </Card>
        </div>

        {/* Right Column - Metadata */}
        <div className="space-y-4">
          {/* Quick Stats */}
          <Card className="border-border shadow-sm bg-gradient-to-br from-primary/5 to-transparent">
            <CardHeader className="pb-2">
              <CardTitle className="text-base">Quick Info</CardTitle>
            </CardHeader>
            <Separator />
            <CardContent className="pt-4 space-y-2.5">
              <div className="flex items-center justify-between p-2 rounded-lg bg-background border border-border">
                <span className="text-xs text-muted-foreground">Temple ID</span>
                <span className="font-mono text-xs font-semibold">{employee.templeId}</span>
              </div>
              <div className="flex items-center justify-between p-2 rounded-lg bg-background border border-border">
                <span className="text-xs text-muted-foreground">Record ID</span>
                <span className="font-mono text-xs font-semibold">{employee.id}</span>
              </div>
              {employee.isHereditary && (
                <div className="p-2.5 rounded-lg bg-amber-50 border border-amber-200">
                  <div className="flex items-center gap-1.5 text-amber-800">
                    <Shield className="size-3.5" />
                    <span className="text-xs font-semibold">Hereditary Position</span>
                  </div>
                  <p className="text-xs text-amber-700 mt-1">
                    This role is passed down through family lineage
                  </p>
                </div>
              )}
            </CardContent>
          </Card>

          {/* Record Information */}
          <Card className="border-border shadow-sm">
            <CardHeader className="pb-2">
              <CardTitle className="text-base flex items-center gap-2">
                <Clock className="size-4 text-primary" />
                Record Information
              </CardTitle>
            </CardHeader>
            <Separator />
            <CardContent className="pt-4 space-y-3">
              {employee.createdAt && (
                <div className="space-y-0.5">
                  <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
                    Created At
                  </p>
                  <p className="text-xs font-medium">
                    {new Date(employee.createdAt).toLocaleString('en-IN', {
                      day: '2-digit',
                      month: 'short',
                      year: 'numeric',
                      hour: '2-digit',
                      minute: '2-digit',
                    })}
                  </p>
                </div>
              )}
              {employee.updatedAt && (
                <div className="space-y-0.5">
                  <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
                    Last Updated
                  </p>
                  <p className="text-xs font-medium">
                    {new Date(employee.updatedAt).toLocaleString('en-IN', {
                      day: '2-digit',
                      month: 'short',
                      year: 'numeric',
                      hour: '2-digit',
                      minute: '2-digit',
                    })}
                  </p>
                </div>
              )}
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  )
}
