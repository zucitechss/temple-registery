import { useState, useEffect } from 'react'
import { toast } from 'sonner'
import {
  useListUsersQuery, useDeactivateUserMutation, useActivateUserMutation,
  useResetUserPasswordMutation,
  useCreateUserMutation, useUpdateUserMutation,
  type UserAdminResponse,
} from '../../adminApi'
import { StatusBadge } from '@/components/data-display/StatusBadge/StatusBadge'
import { Button } from '@/components/ui/button'
import { TableSkeleton } from '@/components/feedback/Skeleton/Skeleton'
import { EmptyState } from '@/components/feedback/EmptyState/EmptyState'
import { Badge } from '@/components/ui/badge'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { Users, Plus, Pencil, Building2, MapPin, ShieldCheck, ClipboardCheck, Landmark, UserCog, Search, X, Eye, KeyRound } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { UserFormDialog } from '../../components/UserFormDialog/UserFormDialog'
import { UserViewModal } from '../../components/UserViewModal/UserViewModal'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog/ConfirmDialog'
import { USER_ROLES, type UserRole } from '@/constants/roles'
import { cn } from '@/lib/utils'
import { extractApiErrorMessage } from '@/lib/apiError'
import { PaginationControl } from '@/components/navigation/PaginationControl/PaginationControl'

// ─── Constants ────────────────────────────────────────────────────────────────

const ROLE_LABELS: Record<string, string> = {
  ALL: 'All Users',
  SUPER_ADMIN: 'Super Admin',
  DISTRICT_COLLECTOR: 'District Collector',
  DC_STAFF: 'DC Staff',
  TEMPLE_AUTHORITY: 'Temple Authority',
  AUDITOR: 'Auditor',
  VIEWER: 'Viewer',
}

const ROLE_SHORT: Record<string, string> = {
  ALL: 'All',
  SUPER_ADMIN: 'Super Admin',
  DISTRICT_COLLECTOR: 'DC',
  DC_STAFF: 'DC Staff',
  TEMPLE_AUTHORITY: 'Temple Auth.',
  AUDITOR: 'Auditor',
  VIEWER: 'Viewer',
}

const ROLE_COLORS: Record<string, string> = {
  SUPER_ADMIN: 'bg-purple-100 text-purple-800 border-purple-200 dark:bg-purple-950 dark:text-purple-300 dark:border-purple-800',
  DISTRICT_COLLECTOR: 'bg-blue-100 text-blue-800 border-blue-200 dark:bg-blue-950 dark:text-blue-300 dark:border-blue-800',
  DC_STAFF: 'bg-sky-100 text-sky-800 border-sky-200 dark:bg-sky-950 dark:text-sky-300 dark:border-sky-800',
  TEMPLE_AUTHORITY: 'bg-amber-100 text-amber-800 border-amber-200 dark:bg-amber-950 dark:text-amber-300 dark:border-amber-800',
  AUDITOR: 'bg-emerald-100 text-emerald-800 border-emerald-200 dark:bg-emerald-950 dark:text-emerald-300 dark:border-emerald-800',
  VIEWER: 'bg-gray-100 text-gray-700 border-gray-200 dark:bg-gray-800 dark:text-gray-300 dark:border-gray-700',
}

type StatusFilter = 'ALL' | 'ACTIVE' | 'INACTIVE'

// ─── Tab Pagination State Interface ──────────────────────────────────────────

interface TabPaginationState {
  currentPage: number;      // 1-indexed for UI
  pageSize: number;          // Fixed at 20
  statusFilter: StatusFilter; // 'ALL' | 'ACTIVE' | 'INACTIVE'
}

// ─── Custom Hook: useTabPaginationState ──────────────────────────────────────

/**
 * Custom hook to manage per-tab pagination state with sessionStorage persistence.
 * 
 * @param tabKey - Unique identifier for the tab (e.g., 'ALL', 'DISTRICT_COLLECTOR')
 * @returns Tuple of [state, setState] similar to useState
 * 
 * **Validates: Requirements 1.4, 1.5, 5.1, 5.4**
 */
function useTabPaginationState(tabKey: string): [TabPaginationState, (state: TabPaginationState) => void] {
  // Initialize state from sessionStorage or defaults
  const [state, setState] = useState<TabPaginationState>(() => {
    try {
      const saved = sessionStorage.getItem(`pagination_${tabKey}`);
      if (saved) {
        const parsed = JSON.parse(saved);
        // Validate parsed data has required fields
        if (
          typeof parsed.currentPage === 'number' &&
          typeof parsed.pageSize === 'number' &&
          typeof parsed.statusFilter === 'string'
        ) {
          return parsed;
        }
      }
    } catch (error) {
      // If parsing fails, fall through to defaults
      console.warn(`Failed to parse pagination state for tab ${tabKey}:`, error);
    }
    
    // Default state
    return {
      currentPage: 1,
      pageSize: 20,
      statusFilter: 'ALL' as StatusFilter,
    };
  });

  // Persist state to sessionStorage whenever it changes
  useEffect(() => {
    try {
      sessionStorage.setItem(`pagination_${tabKey}`, JSON.stringify(state));
    } catch (error) {
      console.warn(`Failed to persist pagination state for tab ${tabKey}:`, error);
    }
  }, [state, tabKey]);

  return [state, setState];
}

// Roles that need a district column
const DISTRICT_ROLES = new Set([USER_ROLES.DISTRICT_COLLECTOR, USER_ROLES.DC_STAFF, USER_ROLES.TEMPLE_AUTHORITY])
// Roles that also need a temple column
const TEMPLE_ROLES = new Set([USER_ROLES.TEMPLE_AUTHORITY])

function maskAadhaar(n?: string): string {
  if (!n) return '—'
  if (n.length === 12) return `xxxx-xxxx-${n.slice(8)}`
  return n
}

function formatDate(dt?: string): string {
  if (!dt) return '—'
  const d = new Date(dt)
  const date = d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })
  const time = d.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: true })
  return `${date}, ${time}`
}

// ─── Avatar initials ──────────────────────────────────────────────────────────

function UserAvatar({ name }: { name: string }) {
  const initials = name.split(' ').slice(0, 2).map(w => w[0]?.toUpperCase() ?? '').join('')
  return (
    <span className="inline-flex items-center justify-center w-8 h-8 rounded-full bg-primary/10 text-primary font-semibold text-xs shrink-0 ring-1 ring-primary/20">
      {initials}
    </span>
  )
}

// ─── Status filter pills ──────────────────────────────────────────────────────

function StatusFilterBar({
  value, onChange, counts,
}: {
  value: StatusFilter
  onChange: (v: StatusFilter) => void
  counts: Record<StatusFilter, number>
}) {
  const pills: { key: StatusFilter; label: string; dotClass: string }[] = [
    { key: 'ALL', label: 'All', dotClass: '' },
    { key: 'ACTIVE', label: 'Active', dotClass: 'bg-emerald-500' },
    { key: 'INACTIVE', label: 'Inactive', dotClass: 'bg-rose-400' },
  ]
  return (
    <div className="flex items-center gap-1.5">
      {pills.map(p => (
        <button
          key={p.key}
          onClick={() => onChange(p.key)}
          className={cn(
            'inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-medium border transition-all duration-150',
            value === p.key
              ? 'bg-primary text-primary-foreground border-primary shadow-sm'
              : 'bg-background text-muted-foreground border-border hover:bg-muted hover:text-foreground',
          )}
        >
          {p.dotClass && (
            <span className={cn('w-1.5 h-1.5 rounded-full', p.dotClass)} />
          )}
          {p.label}
          <span className={cn(
            'ml-0.5 min-w-[18px] text-center rounded-full px-1 text-[10px] font-semibold',
            value === p.key ? 'bg-primary-foreground/20 text-primary-foreground' : 'bg-muted text-muted-foreground',
          )}>
            {counts[p.key]}
          </span>
        </button>
      ))}
    </div>
  )
}

// ─── Users table ──────────────────────────────────────────────────────────────

interface UsersTableProps {
  users: UserAdminResponse[]
  role: UserRole | 'ALL'
  onView: (u: UserAdminResponse) => void
  onEdit: (u: UserAdminResponse) => void
  onToggleStatus: (u: UserAdminResponse) => void
  onResetPassword: (u: UserAdminResponse) => void
  deactivating: boolean
  activating: boolean
}

function UsersTable({ users, role, onView, onEdit, onToggleStatus, onResetPassword, deactivating, activating }: UsersTableProps) {
  const showDistrict = role === 'ALL' || DISTRICT_ROLES.has(role as 'DISTRICT_COLLECTOR' | 'DC_STAFF' | 'TEMPLE_AUTHORITY')
  const showTemple = role === 'ALL' || TEMPLE_ROLES.has(role as 'TEMPLE_AUTHORITY')

  if (users.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center gap-3 text-muted-foreground">
        <div className="p-4 rounded-full bg-muted/60">
          <Users size={28} className="opacity-40" />
        </div>
        <p className="text-sm font-medium">No users found</p>
        <p className="text-xs opacity-70">Try adjusting the filters above</p>
      </div>
    )
  }

  return (
    <div className="overflow-x-auto scrollbar-thin rounded-xl border border-border shadow-sm">
      <table className="w-full text-sm" style={{ minWidth: '960px' }}>
        <thead>
          <tr className="bg-muted/40 border-b border-border">
            <th className="px-4 py-3 text-left text-xs font-semibold text-muted-foreground uppercase tracking-wide whitespace-nowrap w-[260px]">User</th>
            {role === 'ALL' && (
              <th className="px-4 py-3 text-left text-xs font-semibold text-muted-foreground uppercase tracking-wide whitespace-nowrap w-[120px]">Role</th>
            )}
            {showDistrict && (
              <th className="px-4 py-3 text-left text-xs font-semibold text-muted-foreground uppercase tracking-wide whitespace-nowrap w-[130px]">District</th>
            )}
            {showTemple && (
              <th className="px-4 py-3 text-left text-xs font-semibold text-muted-foreground uppercase tracking-wide whitespace-nowrap w-[160px]">Temple</th>
            )}
            <th className="px-4 py-3 text-left text-xs font-semibold text-muted-foreground uppercase tracking-wide whitespace-nowrap w-[90px]">Status</th>
            <th className="px-4 py-3 text-left text-xs font-semibold text-muted-foreground uppercase tracking-wide whitespace-nowrap w-[140px]">Aadhaar</th>
            <th className="px-4 py-3 text-left text-xs font-semibold text-muted-foreground uppercase tracking-wide whitespace-nowrap w-[175px]">Last Login</th>
            <th className="px-4 py-3 whitespace-nowrap w-[140px]" />
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {users.map((user) => (
            <tr key={user.id} className="group hover:bg-muted/30 transition-colors duration-100">
              {/* User */}
              <td className="px-4 py-3 whitespace-nowrap">
                <div className="flex items-center gap-3">
                  <UserAvatar name={user.fullName} />
                  <div className="min-w-0">
                    <p className="font-medium text-foreground truncate max-w-[180px]">{user.fullName}</p>
                    <p className="text-xs text-muted-foreground truncate max-w-[180px]">{user.email}</p>
                  </div>
                </div>
              </td>

              {/* Role — only on All tab */}
              {role === 'ALL' && (
                <td className="px-4 py-3 whitespace-nowrap">
                  <Badge
                    variant="outline"
                    className={cn('text-[11px] font-medium border whitespace-nowrap', ROLE_COLORS[user.role] ?? '')}
                  >
                    {ROLE_SHORT[user.role] ?? user.role}
                  </Badge>
                </td>
              )}

              {/* District */}
              {showDistrict && (
                <td className="px-4 py-3 whitespace-nowrap">
                  {user.districtName ? (
                    <span className="inline-flex items-center gap-1 text-xs text-foreground">
                      <MapPin size={11} className="text-muted-foreground shrink-0" />
                      <span className="truncate max-w-[110px]">{user.districtName}</span>
                    </span>
                  ) : (
                    <span className="text-xs text-muted-foreground/50">—</span>
                  )}
                </td>
              )}

              {/* Temple */}
              {showTemple && (
                <td className="px-4 py-3 whitespace-nowrap">
                  {user.templeName ? (
                    <span className="inline-flex items-center gap-1 text-xs text-foreground">
                      <Building2 size={11} className="text-muted-foreground shrink-0" />
                      <span className="truncate max-w-[130px]">{user.templeName}</span>
                    </span>
                  ) : (
                    <span className="text-xs text-muted-foreground/50">—</span>
                  )}
                </td>
              )}

              {/* Status */}
              <td className="px-4 py-3 whitespace-nowrap">
                <StatusBadge status={user.active ? 'ACTIVE' : 'INACTIVE'} />
              </td>

              {/* Aadhaar */}
              <td className="px-4 py-3 whitespace-nowrap">
                <span className="font-mono text-xs text-muted-foreground tabular-nums">
                  {maskAadhaar(user.aadhaarNumber)}
                </span>
              </td>

              {/* Last Login */}
              <td className="px-4 py-3 whitespace-nowrap">
                {user.lastLoginAt ? (
                  <span className="text-xs text-muted-foreground">{formatDate(user.lastLoginAt)}</span>
                ) : (
                  <span className="text-xs text-amber-600 dark:text-amber-500 font-medium">Never</span>
                )}
              </td>

              {/* Actions */}
              <td className="px-4 py-3 whitespace-nowrap">
                <div className="flex items-center justify-end gap-1.5 opacity-0 group-hover:opacity-100 transition-opacity duration-100">
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-7 w-7"
                    onClick={() => onView(user)}
                    title="View user"
                  >
                    <Eye size={13} />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-7 w-7"
                    onClick={() => onEdit(user)}
                    title="Edit user"
                  >
                    <Pencil size={13} />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-7 w-7"
                    onClick={() => onResetPassword(user)}
                    title="Reset password"
                    aria-label={`Reset password for ${user.fullName}`}
                  >
                    <KeyRound size={13} />
                  </Button>
                  <Button
                    variant={user.active ? 'destructive' : 'outline'}
                    size="sm"
                    className="h-7 text-xs px-2.5 whitespace-nowrap"
                    onClick={() => onToggleStatus(user)}
                    disabled={deactivating || activating}
                  >
                    {user.active ? 'Deactivate' : 'Activate'}
                  </Button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ─── Per-role tab content ──────────────────────────────────────────────────────

function RoleTabContent({
  role, onView, onEdit, onToggleStatus, onResetPassword, deactivating, activating,
}: {
  role: UserRole | 'ALL'
  onView: (u: UserAdminResponse) => void
  onEdit: (u: UserAdminResponse) => void
  onToggleStatus: (u: UserAdminResponse) => void
  onResetPassword: (u: UserAdminResponse) => void
  deactivating: boolean
  activating: boolean
}) {
  const [search, setSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')

  // Debounce search input — avoids a server request on every keystroke
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search), 300)
    return () => clearTimeout(timer)
  }, [search])

  // Use per-tab pagination state with sessionStorage persistence
  const [paginationState, setPaginationState] = useTabPaginationState(role)
  const { currentPage, statusFilter } = paginationState

  // Server-side search + role filter; status is kept client-side (simple flag)
  const { data, isLoading, isError, refetch } = useListUsersQuery({
    page: currentPage - 1,
    size: 20,
    ...(debouncedSearch.trim() ? { search: debouncedSearch.trim() } : {}),
    ...(role !== 'ALL' ? { role } : {}),
  })

  const allUsers = data?.data?.content ?? []
  const totalPages = data?.data?.totalPages ?? 0

  // Status is the only remaining client-side filter
  const filtered = statusFilter === 'ALL'
    ? allUsers
    : allUsers.filter(u => (statusFilter === 'ACTIVE' ? u.active : !u.active))

  // Status counts based on the current server-returned page
  const counts: Record<StatusFilter, number> = {
    ALL: allUsers.length,
    ACTIVE: allUsers.filter(u => u.active).length,
    INACTIVE: allUsers.filter(u => !u.active).length,
  }

  // Reset to page 1 when debounced search changes
  useEffect(() => {
    setPaginationState({ ...paginationState, currentPage: 1 })
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedSearch])

  // Handle invalid page numbers (e.g., after filtering reduces total pages)
  useEffect(() => {
    if (totalPages > 0 && currentPage > totalPages) {
      setPaginationState({ ...paginationState, currentPage: totalPages })
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [totalPages, currentPage])

  // Handle status filter change
  const handleStatusFilterChange = (newFilter: StatusFilter) => {
    setPaginationState({ ...paginationState, statusFilter: newFilter, currentPage: 1 })
  }

  // Handle page change
  const handlePageChange = (page: number) => {
    setPaginationState({ ...paginationState, currentPage: page })
  }

  return (
    <div className="space-y-3 pt-4">
      {/* Search bar */}
      <div className="relative">
        <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground pointer-events-none" />
        <Input
          value={search}
          onChange={e => setSearch(e.target.value)}
          placeholder="Search by name, email or username…"
          className="pl-8 h-9 text-sm bg-muted/30 border-border/70 focus:bg-background transition-colors"
        />
        {search && (
          <button
            onClick={() => setSearch('')}
            className="absolute right-2.5 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground transition-colors"
            aria-label="Clear search"
          >
            <X size={13} />
          </button>
        )}
      </div>

      {/* Error state */}
      {isError ? (
        <EmptyState
          title="Failed to load users"
          description="Unable to fetch user data. Please try again."
          action={{ label: 'Retry', onClick: () => refetch() }}
        />
      ) : (
        <>
          <StatusFilterBar 
            value={statusFilter} 
            onChange={handleStatusFilterChange} 
            counts={counts} 
          />
          
          {/* Loading state - show skeleton while maintaining layout */}
          {isLoading ? (
            <TableSkeleton rows={20} />
          ) : (
            <UsersTable
              users={filtered}
              role={role}
              onView={onView}
              onEdit={onEdit}
              onToggleStatus={onToggleStatus}
              onResetPassword={onResetPassword}
              deactivating={deactivating}
              activating={activating}
            />
          )}
          
          {/* Conditionally render pagination only when totalPages > 1 */}
          {totalPages > 1 && (
            <PaginationControl
              currentPage={currentPage}
              totalPages={totalPages}
              onPageChange={handlePageChange}
              disabled={isLoading}
            />
          )}
        </>
      )}
    </div>
  )
}

// ─── Main page ────────────────────────────────────────────────────────────────

export function UserManagementPage() {
  // Fetch initial user count for header (using page 0, size 1 to get total count efficiently)
  const { data: countData, isLoading: countLoading } = useListUsersQuery({ page: 0, size: 1 })
  const [deactivate, { isLoading: deactivating }] = useDeactivateUserMutation()
  const [activate, { isLoading: activating }] = useActivateUserMutation()
  const [createUser, { isLoading: creating }] = useCreateUserMutation()
  const [updateUser, { isLoading: updating }] = useUpdateUserMutation()
  const [resetUserPassword, { isLoading: resettingPassword }] = useResetUserPasswordMutation()

  const [dialogOpen, setDialogOpen] = useState(false)
  const [selectedUser, setSelectedUser] = useState<UserAdminResponse | null>(null)
  const [viewUser, setViewUser] = useState<UserAdminResponse | null>(null)
  const [confirmStatusUser, setConfirmStatusUser] = useState<UserAdminResponse | null>(null)
  const [confirmResetUser, setConfirmResetUser] = useState<UserAdminResponse | null>(null)

  const totalUsers = countData?.data?.totalElements ?? 0

  const handleCreate = () => { setSelectedUser(null); setDialogOpen(true) }
  const handleView = (user: UserAdminResponse) => setViewUser(user)
  const handleEdit = (user: UserAdminResponse) => { setSelectedUser(user); setDialogOpen(true) }
  const handleToggleStatus = (user: UserAdminResponse) => setConfirmStatusUser(user)
  const handleResetPassword = (user: UserAdminResponse) => setConfirmResetUser(user)

  const resetUserPasswordFor = async (user: UserAdminResponse) => {
    try {
      await resetUserPassword(user.id).unwrap()
      // The temporary password itself is never returned to the browser — only emailed.
      toast.success(`Temporary password sent to ${user.email}`)
    } catch (err) {
      toast.error(extractApiErrorMessage(err, 'Failed to reset the password. Please try again.'))
    } finally {
      setConfirmResetUser(null)
    }
  }

  const toggleUserStatus = async (user: UserAdminResponse) => {
    try {
      if (user.active) {
        await deactivate(user.id).unwrap()
        toast.success(`${user.fullName} deactivated`)
      } else {
        await activate(user.id).unwrap()
        toast.success(`${user.fullName} activated`)
      }
    } catch {
      toast.error('Failed to update user status')
    } finally {
      setConfirmStatusUser(null)
    }
  }

  const handleFormSubmit = async (values: any) => {
    try {
      if (selectedUser) {
        await updateUser({ id: selectedUser.id, body: values }).unwrap()
        toast.success(`${selectedUser.fullName} updated successfully`)
      } else {
        const created = await createUser(values).unwrap()
        const name = created?.data?.fullName ?? values.fullName ?? 'User'
        toast.success(`${name} created successfully`)
      }
      setDialogOpen(false)
    } catch (err: unknown) {
      const apiErr = err as { data?: { message?: string }; message?: string }
      const msg = apiErr?.data?.message ?? apiErr?.message ?? 'Failed to save user'
      toast.error(msg)
    }
  }

  const [activeTab, setActiveTab] = useState('ALL')

  const ROLE_TABS = [
    { value: 'ALL',                           label: 'All Users',          icon: <Users size={14} />,         count: totalUsers },
    { value: USER_ROLES.SUPER_ADMIN,          label: 'Super Admin',        icon: <ShieldCheck size={14} />,   count: null },
    { value: USER_ROLES.DISTRICT_COLLECTOR,   label: 'District Collector', icon: <Building2 size={14} />,     count: null },
    { value: USER_ROLES.DC_STAFF,             label: 'DC Staff',           icon: <UserCog size={14} />,       count: null },
    { value: USER_ROLES.TEMPLE_AUTHORITY,     label: 'Temple Authority',   icon: <Landmark size={14} />,      count: null },
    { value: USER_ROLES.AUDITOR,              label: 'Auditor',            icon: <ClipboardCheck size={14} />,count: null },
  ] as const

  const tabProps = { onView: handleView, onEdit: handleEdit, onToggleStatus: handleToggleStatus, onResetPassword: handleResetPassword, deactivating, activating }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="p-2 rounded-xl bg-primary/10 ring-1 ring-primary/20">
            <Users size={18} className="text-primary" />
          </div>
          <div>
            <h1 className="text-base font-semibold text-foreground">User Management</h1>
            <p className="text-xs text-muted-foreground mt-0.5">
              {countLoading ? 'Loading…' : `${totalUsers} user${totalUsers !== 1 ? 's' : ''} registered`}
            </p>
          </div>
        </div>
        <Button onClick={handleCreate} size="sm" className="gap-1.5 h-8 text-sm">
          <Plus size={14} />
          Create User
        </Button>
      </div>

      <Tabs value={activeTab} onValueChange={setActiveTab} className="w-full">
        {/* Tab navigation — styled like DC Temple Profile */}
        <div
          className="sticky top-0 z-30 rounded-xl overflow-hidden shadow-lg"
          style={{
            background: 'rgba(28, 25, 23, 0.97)',
            backdropFilter: 'blur(12px)',
            WebkitBackdropFilter: 'blur(12px)',
            border: '1px solid rgba(255, 255, 255, 0.07)',
          }}
        >
          <div className="overflow-x-auto overflow-y-hidden scrollbar-thin px-2">
            <TabsList className="h-12 p-0 bg-transparent gap-0.5 flex min-w-max">
              {ROLE_TABS.map(tab => (
                <TabsTrigger
                  key={tab.value}
                  value={tab.value}
                  className={cn(
                    'relative h-12 flex items-center gap-2 px-4 text-xs font-semibold transition-all duration-200 tracking-wide whitespace-nowrap rounded-none',
                    'text-slate-400 hover:text-white hover:bg-white/5',
                    'data-[state=active]:text-white data-[state=active]:bg-transparent shadow-none',
                    'focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-orange-500/50',
                  )}
                  style={activeTab === tab.value ? {
                    background: 'linear-gradient(to bottom, rgba(251, 146, 60, 0.14), rgba(249, 115, 22, 0.06))',
                    borderLeft: '1px solid rgba(251, 146, 60, 0.18)',
                    borderRight: '1px solid rgba(251, 146, 60, 0.18)',
                    borderTop: '1px solid rgba(251, 146, 60, 0.18)',
                  } : {}}
                >
                  {tab.icon}
                  <span>{tab.label}</span>
                  {tab.count !== null && tab.count > 0 && (
                    <span className={cn(
                      'ml-1 text-[10px] font-bold px-1.5 py-0.5 rounded-md transition-all',
                      activeTab === tab.value
                        ? 'bg-orange-500 text-white shadow-sm'
                        : 'bg-slate-800/80 text-slate-400 border border-slate-700/50',
                    )}>
                      {tab.count}
                    </span>
                  )}
                  {activeTab === tab.value && (
                    <div
                      className="absolute bottom-0 inset-x-0 h-0.5"
                      style={{
                        background: 'linear-gradient(90deg, hsl(36 80% 50%), hsl(24 85% 55%))',
                        boxShadow: '0 0 10px rgba(251, 146, 60, 0.55)',
                      }}
                    />
                  )}
                </TabsTrigger>
              ))}
            </TabsList>
          </div>
        </div>

        {ROLE_TABS.map(tab => (
          <TabsContent
            key={tab.value}
            value={tab.value}
            forceMount
            className="mt-0 outline-none ring-0 focus-visible:ring-0 data-[state=inactive]:hidden data-[state=active]:animate-in data-[state=active]:fade-in-0 data-[state=active]:duration-200"
          >
            <RoleTabContent role={tab.value as UserRole | 'ALL'} {...tabProps} />
          </TabsContent>
        ))}
      </Tabs>

      <UserFormDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        user={selectedUser}
        onSubmit={handleFormSubmit}
        isLoading={creating || updating}
      />

      <UserViewModal
        user={viewUser}
        open={viewUser !== null}
        onOpenChange={(open) => { if (!open) setViewUser(null) }}
      />

      <ConfirmDialog
        open={confirmStatusUser !== null}
        onOpenChange={(open) => { if (!open) setConfirmStatusUser(null) }}
        title={confirmStatusUser?.active
          ? `Deactivate ${confirmStatusUser?.fullName}?`
          : `Activate ${confirmStatusUser?.fullName}?`}
        description={confirmStatusUser?.active
          ? `${confirmStatusUser?.username} will lose login access immediately.`
          : `Login access will be restored for ${confirmStatusUser?.username}.`}
        confirmLabel={confirmStatusUser?.active ? 'Deactivate' : 'Activate'}
        confirmVariant={confirmStatusUser?.active ? 'destructive' : 'default'}
        onConfirm={() => confirmStatusUser && toggleUserStatus(confirmStatusUser)}
      />

      <ConfirmDialog
        open={confirmResetUser !== null}
        onOpenChange={(open) => { if (!open) setConfirmResetUser(null) }}
        title={`Reset password for ${confirmResetUser?.fullName}?`}
        description={`A temporary password will be generated and emailed to ${confirmResetUser?.email}. Their current password stops working immediately, all their sessions are signed out, and they must set a new password at next login.`}
        confirmLabel={resettingPassword ? 'Resetting…' : 'Reset Password'}
        confirmVariant="destructive"
        onConfirm={() => confirmResetUser && resetUserPasswordFor(confirmResetUser)}
      />
    </div>
  )
}