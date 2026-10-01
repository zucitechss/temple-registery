import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Building2,
  KeyRound,
  Loader2,
  Mail,
  MapPin,
  Phone,
  ShieldCheck,
  User as UserIcon,
} from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { StatusBadge } from '@/components/data-display/StatusBadge/StatusBadge'
import { EmptyState } from '@/components/feedback/EmptyState/EmptyState'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { useGetCurrentUserQuery } from '@/features/auth/authApi'
import { USER_ROLES } from '@/constants/roles'
import { ROUTE_PATHS } from '@/constants/routePaths'
import type { CurrentUser } from '@/features/auth/authTypes'
import { ChangePasswordForm } from '../../components/ChangePasswordForm/ChangePasswordForm'

const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: 'Super Admin',
  DISTRICT_COLLECTOR: 'District Collector',
  DC_STAFF: 'DC Staff',
  TEMPLE_AUTHORITY: 'Temple Authority',
  AUDITOR: 'Auditor',
  VIEWER: 'Viewer',
}

function formatDateTime(iso?: string) {
  if (!iso) return null
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return null
  return date.toLocaleString('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

interface FieldProps {
  label: string
  value?: string | null
  icon?: React.ReactNode
}

/** One label/value pair. Renders an em dash when the underlying field is not set. */
function Field({ label, value, icon }: FieldProps) {
  return (
    <div className="space-y-1">
      <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</p>
      <p className="flex items-center gap-1.5 text-sm font-medium text-foreground break-words">
        {value ? (
          <>
            {icon}
            {value}
          </>
        ) : (
          <span className="text-muted-foreground/60">—</span>
        )}
      </p>
    </div>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <Card className="p-5 sm:p-6">
      <h2 className="text-base font-semibold text-foreground">{title}</h2>
      <Separator className="my-4" />
      {children}
    </Card>
  )
}

/** Fields that only make sense for some roles are omitted entirely when unassigned. */
function AssignmentSection({ user }: { user: CurrentUser }) {
  const isTempleAuthority = user.role === USER_ROLES.TEMPLE_AUTHORITY

  return (
    <Section title="Role & Assignment">
      <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
        <Field label="Role" value={ROLE_LABELS[user.role] ?? user.role} />
        {user.designation && <Field label="Designation" value={user.designation} />}
        {(user.districtName || user.districtId) && (
          <Field
            label="District"
            value={user.districtName ?? `#${user.districtId}`}
            icon={<MapPin size={13} className="text-muted-foreground shrink-0" />}
          />
        )}
        {(user.templeName || user.templeId) && (
          <Field
            label="Temple"
            value={user.templeName ?? `#${user.templeId}`}
            icon={<Building2 size={13} className="text-muted-foreground shrink-0" />}
          />
        )}
        {isTempleAuthority && user.accessType && (
          <Field
            label="Access Level"
            value={user.accessType === 'VIEW' ? 'View only' : 'Full access'}
          />
        )}
        {isTempleAuthority && (
          <Field label="Aadhaar Verification" value={user.aadhaarVerified ? 'Verified' : 'Not verified'} />
        )}
      </div>
    </Section>
  )
}

export function ProfilePage() {
  // Reuses the same endpoint PrivateRoute already calls — no duplicate profile request.
  const { data, isLoading, isError, refetch } = useGetCurrentUserQuery()
  const [changePasswordOpen, setChangePasswordOpen] = useState(false)

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-20">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    )
  }

  const user = data?.data
  if (isError || !user) {
    return (
      <EmptyState
        title="Could not load your profile"
        description="Something went wrong while fetching your account details."
        action={{ label: 'Try again', onClick: () => void refetch() }}
      />
    )
  }

  const lastPasswordChange = formatDateTime(user.passwordUpdatedAt)
  const lastLogin = formatDateTime(user.lastLoginAt)

  return (
    <div className="space-y-6">
      {/* Identity header */}
      <Card className="p-5 sm:p-6">
        <div className="flex flex-col items-center gap-4 text-center sm:flex-row sm:items-center sm:text-left">
          <div
            className="flex h-16 w-16 shrink-0 items-center justify-center rounded-full bg-gradient-gold shadow-gold text-xl font-bold text-white"
            aria-hidden="true"
          >
            {user.fullName.charAt(0).toUpperCase()}
          </div>
          <div className="min-w-0 flex-1 space-y-1">
            <h1 className="truncate text-xl font-semibold text-foreground sm:text-2xl">
              {user.fullName}
            </h1>
            {user.email && (
              <p className="flex items-center justify-center gap-1.5 truncate text-sm text-muted-foreground sm:justify-start">
                <Mail size={13} className="shrink-0" />
                {user.email}
              </p>
            )}
            <div className="flex flex-wrap items-center justify-center gap-2 pt-1 sm:justify-start">
              <Badge variant="outline" className="text-[11px] font-medium">
                {ROLE_LABELS[user.role] ?? user.role}
              </Badge>
              <StatusBadge status={user.active === false ? 'INACTIVE' : 'ACTIVE'} />
            </div>
          </div>
        </div>
      </Card>

      <Section title="Personal Information">
        <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
          <Field
            label="Full Name"
            value={user.fullName}
            icon={<UserIcon size={13} className="text-muted-foreground shrink-0" />}
          />
          <Field label="Username" value={user.username} />
          <Field
            label="Email"
            value={user.email}
            icon={<Mail size={13} className="text-muted-foreground shrink-0" />}
          />
          <Field
            label="Mobile"
            value={user.mobile}
            icon={<Phone size={13} className="text-muted-foreground shrink-0" />}
          />
          <Field label="User ID" value={String(user.userId)} />
          <Field label="Last Login" value={lastLogin} />
        </div>
      </Section>

      <AssignmentSection user={user} />

      <Section title="Security">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
          <div className="grid flex-1 grid-cols-1 gap-5 sm:grid-cols-2">
            <Field
              label="Password"
              value="••••••••"
              icon={<ShieldCheck size={13} className="text-muted-foreground shrink-0" />}
            />
            <Field label="Last Password Change" value={lastPasswordChange ?? 'Never changed'} />
          </div>
          <Button onClick={() => setChangePasswordOpen(true)} className="gap-2 sm:shrink-0">
            <KeyRound size={15} />
            Change Password
          </Button>
        </div>
      </Section>

      {/* Administrative controls are rendered for Super Admin only — and the backend
          enforces the same rule, so hiding this is convenience, not security. */}
      {user.role === USER_ROLES.SUPER_ADMIN && (
        <Section title="Administrative Actions">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <p className="text-sm text-muted-foreground">
              Reset another user's password and email them a temporary one from User Management.
            </p>
            <Button asChild variant="outline" className="gap-2 sm:shrink-0">
              <Link to={ROUTE_PATHS.ADMIN_USERS}>
                <KeyRound size={15} />
                Reset User Password
              </Link>
            </Button>
          </div>
        </Section>
      )}

      <Dialog open={changePasswordOpen} onOpenChange={setChangePasswordOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Change Password</DialogTitle>
            <DialogDescription>
              All your sessions are signed out when the password changes — you will be asked to
              sign in again with the new password.
            </DialogDescription>
          </DialogHeader>
          <ChangePasswordForm
            onSuccess={() => setChangePasswordOpen(false)}
            secondaryAction={
              <DialogClose asChild>
                <Button type="button" variant="outline">
                  Cancel
                </Button>
              </DialogClose>
            }
          />
        </DialogContent>
      </Dialog>
    </div>
  )
}
