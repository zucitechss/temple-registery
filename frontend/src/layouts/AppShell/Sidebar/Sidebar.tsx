import { NavLink, useNavigate } from 'react-router-dom'
import { cn } from '@/lib/utils'
import {
  LayoutDashboard, Search, FileText, Users, Building2,
  ClipboardList, Download, Settings, LogOut, Shield, Clock, Activity, RefreshCw, ChevronLeft, ChevronRight,
  Eye, ShieldCheck, History, Bell, AlertTriangle, Lock, Megaphone, ListTree
} from 'lucide-react'
import { useLogout } from '@/features/auth/authHooks'
import { useAppSelector } from '@/app/store'
import { USER_ROLES } from '@/constants/roles'
import { ROUTE_PATHS } from '@/constants/routePaths'
import { Button } from '@/components/ui/button'
import { useGetStatewideDashboardQuery } from '@/features/admin/adminApi'
import { usePermissions } from '@/features/access-control/hooks/usePermissions'
import { TARGET_KEYS } from '@/features/access-control/constants/targetKeys'

interface NavItem {
  label: string
  to: string
  icon: React.ReactNode
  badge?: number
  targetKey?: string
}

function getDcNavItems(): NavItem[] {
  return [
    { label: 'Dashboard', to: ROUTE_PATHS.DC_DASHBOARD, icon: <LayoutDashboard size={16} />, targetKey: TARGET_KEYS.PAGE_DC_DASHBOARD },
    { label: 'Notices',   to: ROUTE_PATHS.DC_NOTICES,   icon: <Megaphone size={16} /> },
    { label: 'Temples',   to: ROUTE_PATHS.DC_TEMPLES,   icon: <Search size={16} /> },
    { label: 'Export',    to: ROUTE_PATHS.DC_EXPORT,    icon: <Download size={16} />,       targetKey: TARGET_KEYS.PAGE_DC_EXPORT },
    { label: 'Source Mapper', to: ROUTE_PATHS.FINANCE_SOURCE_MAPPER, icon: <ListTree size={16} /> },
    { label: 'Activity',  to: ROUTE_PATHS.DC_ACTIVITY,  icon: <Activity size={16} />,       targetKey: TARGET_KEYS.PAGE_DC_ACTIVITY },
  ]
}

function getTaNavItems(): NavItem[] {
  return [
    { label: 'Dashboard',     to: ROUTE_PATHS.TA_DASHBOARD,    icon: <LayoutDashboard size={16} />, targetKey: TARGET_KEYS.PAGE_TA_DASHBOARD },
    { label: 'Temple Profile',to: ROUTE_PATHS.TA_TEMPLE,       icon: <Building2 size={16} /> },
    { label: 'Temple Search', to: ROUTE_PATHS.DC_TEMPLES,      icon: <Search size={16} />,         targetKey: TARGET_KEYS.PAGE_TA_TEMPLE_SEARCH },
    { label: 'Trust & Board', to: ROUTE_PATHS.TA_TRUST,        icon: <Shield size={16} />,         targetKey: TARGET_KEYS.PAGE_TA_TRUST },
    { label: 'Employees',     to: ROUTE_PATHS.TA_EMPLOYEES,    icon: <Users size={16} />,          targetKey: TARGET_KEYS.PAGE_TA_EMPLOYEES },
    { label: 'Contractors',   to: ROUTE_PATHS.TA_CONTRACTORS,  icon: <FileText size={16} />,       targetKey: TARGET_KEYS.PAGE_TA_CONTRACTORS },
    { label: 'Declarations',  to: ROUTE_PATHS.TA_DECLARATIONS, icon: <ClipboardList size={16} />,  targetKey: TARGET_KEYS.PAGE_TA_DECLARATIONS },
    { label: 'Documents',     to: ROUTE_PATHS.TA_DOCUMENTS,    icon: <Download size={16} />,       targetKey: TARGET_KEYS.PAGE_TA_DOCUMENTS },
    { label: 'Profile Status',to: ROUTE_PATHS.TA_PROFILE_STATUS,icon: <Clock size={16} /> },
    { label: 'Activity',      to: ROUTE_PATHS.TA_ACTIVITY,     icon: <Activity size={16} /> },
  ]
}

function getAdminNavItems(pendingCount?: number): NavItem[] {
  return [
    { label: 'Dashboard', to: ROUTE_PATHS.ADMIN_DASHBOARD, icon: <LayoutDashboard size={16} /> },
    { label: 'Notices', to: ROUTE_PATHS.ADMIN_NOTICES, icon: <Megaphone size={16} /> },
    { label: 'Users', to: ROUTE_PATHS.ADMIN_USERS, icon: <Users size={16} />, badge: undefined },
    { label: 'Temple Governance', to: ROUTE_PATHS.ADMIN_TEMPLE_GOVERNANCE, icon: <AlertTriangle size={16} /> },
    { label: 'Admin Tools', to: ROUTE_PATHS.ADMIN_TOOLS, icon: <RefreshCw size={16} />, badge: pendingCount && pendingCount > 0 ? pendingCount : undefined },
    { label: 'Audit Logs', to: ROUTE_PATHS.ADMIN_AUDIT, icon: <Shield size={16} /> },
    { label: 'Geo Master', to: ROUTE_PATHS.ADMIN_GEO, icon: <Settings size={16} /> },
    { label: 'System Config', to: ROUTE_PATHS.ADMIN_SYSTEM_CONFIG, icon: <Settings size={16} /> },
    { label: 'Notification Rules', to: ROUTE_PATHS.ADMIN_NOTIFICATION_RULES, icon: <Bell size={16} /> },
    { label: 'Access Control', to: ROUTE_PATHS.ADMIN_ACCESS_CONTROL, icon: <Lock size={16} /> },
    // Data access — SA can also navigate DC/Auditor pages
    { label: 'Temple Search', to: ROUTE_PATHS.DC_TEMPLES, icon: <Search size={16} /> },
    { label: 'Declarations', to: ROUTE_PATHS.DC_DECLARATIONS, icon: <ClipboardList size={16} /> },
    { label: 'Export', to: ROUTE_PATHS.DC_EXPORT, icon: <Download size={16} /> },
    { label: 'Source Mapper', to: ROUTE_PATHS.FINANCE_SOURCE_MAPPER, icon: <ListTree size={16} /> },
    { label: 'Compliance', to: ROUTE_PATHS.AUDITOR_COMPLIANCE, icon: <ShieldCheck size={16} /> },
  ]
}

function getAuditorNavItems(): NavItem[] {
  return [
    { label: 'Dashboard',    to: ROUTE_PATHS.AUDITOR_DASHBOARD,    icon: <LayoutDashboard size={16} />, targetKey: TARGET_KEYS.PAGE_AUDITOR_DASHBOARD },
    { label: 'Temples',      to: ROUTE_PATHS.AUDITOR_TEMPLES,      icon: <Building2 size={16} /> },
    { label: 'Declarations', to: ROUTE_PATHS.AUDITOR_DECLARATIONS,  icon: <ClipboardList size={16} /> },
    { label: 'Observations', to: ROUTE_PATHS.AUDITOR_OBSERVATIONS,  icon: <Eye size={16} />,            targetKey: TARGET_KEYS.PAGE_AUDITOR_OBSERVATIONS },
    { label: 'Compliance',   to: ROUTE_PATHS.AUDITOR_COMPLIANCE,    icon: <ShieldCheck size={16} />,    targetKey: TARGET_KEYS.PAGE_AUDITOR_COMPLIANCE },
    { label: 'Audit Trail',  to: ROUTE_PATHS.AUDITOR_AUDIT_TRAIL,   icon: <History size={16} />,        targetKey: TARGET_KEYS.PAGE_AUDITOR_AUDIT_TRAIL },
    { label: 'Source Mapper', to: ROUTE_PATHS.FINANCE_SOURCE_MAPPER, icon: <ListTree size={16} /> },
  ]
}

function getViewerNavItems(): NavItem[] {
  return [
    { label: 'Dashboard',    to: ROUTE_PATHS.VIEWER_DASHBOARD,    icon: <LayoutDashboard size={16} />, targetKey: TARGET_KEYS.PAGE_VIEWER_DASHBOARD },
    { label: 'Temples',      to: ROUTE_PATHS.VIEWER_TEMPLES,      icon: <Building2 size={16} /> },
    { label: 'Declarations', to: ROUTE_PATHS.VIEWER_DECLARATIONS,  icon: <ClipboardList size={16} /> },
    { label: 'Compliance',   to: ROUTE_PATHS.VIEWER_COMPLIANCE,    icon: <ShieldCheck size={16} /> },
    { label: 'Audit Trail',  to: ROUTE_PATHS.VIEWER_AUDIT_TRAIL,   icon: <History size={16} /> },
    { label: 'Export',       to: ROUTE_PATHS.VIEWER_EXPORT,        icon: <Download size={16} />,       targetKey: TARGET_KEYS.PAGE_VIEWER_EXPORT },
  ]
}

import { useEffect } from 'react'

interface SidebarProps {
  open?: boolean
  setOpen?: (open: boolean) => void
  collapsed?: boolean
  onToggleCollapse?: () => void
}

export function Sidebar({ open, setOpen, collapsed, onToggleCollapse }: SidebarProps) {
  const { handleLogout } = useLogout()
  const currentUser = useAppSelector((s) => s.auth.currentUser)
  const role = currentUser?.role
  const { can, isLoading: permissionsLoading } = usePermissions()

  const { data: dashData } = useGetStatewideDashboardQuery(undefined, { skip: role !== USER_ROLES.SUPER_ADMIN })
  const pendingCount = role === USER_ROLES.SUPER_ADMIN
    ? ((dashData?.data?.totalPendingDeclarations ?? 0) + (dashData?.data?.totalPendingProfileReviews ?? 0))
    : 0

  let allNavItems: NavItem[] = []
  if (role === USER_ROLES.DISTRICT_COLLECTOR || role === USER_ROLES.DC_STAFF) allNavItems = getDcNavItems()
  else if (role === USER_ROLES.TEMPLE_AUTHORITY) allNavItems = getTaNavItems()
  else if (role === USER_ROLES.SUPER_ADMIN) allNavItems = getAdminNavItems(pendingCount)
  else if (role === USER_ROLES.AUDITOR) allNavItems = getAuditorNavItems()
  else if (role === USER_ROLES.VIEWER) allNavItems = getViewerNavItems()

  // Filter nav items by permission. Fails-open while loading (no targetKey = always shown).
  const navItems = permissionsLoading
    ? allNavItems
    : allNavItems.filter((item) => !item.targetKey || can(item.targetKey))

  // Prevent body scroll when sidebar drawer is open on mobile
  useEffect(() => {
    if (!open) return
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = ''
    }
  }, [open])

  return (
    <aside
      className={cn(
        // Mobile: off-canvas drawer
        'fixed inset-y-0 left-0 z-40 flex h-screen flex-col bg-sidebar text-sidebar-foreground border-r border-sidebar-border transition-all duration-300 ease-in-out',
        'lg:static lg:translate-x-0 shadow-soft-lg lg:shadow-none',
        // Mobile drawer behavior
        open ? 'translate-x-0' : '-translate-x-full',
        // Desktop collapsed/expanded width
        collapsed ? 'lg:w-20' : 'lg:w-64',
        // Mobile always full width when open
        'w-64',
      )}
      aria-label="Sidebar"
    >
      {/* Logo Section */}
      <div className={cn(
        "flex h-16 items-center border-b border-sidebar-border/50 transition-all duration-300 relative",
        collapsed ? "lg:justify-center lg:px-3" : "justify-between px-4"
      )}>
        <div className={cn(
          "flex items-center gap-2.5 min-w-0 transition-all duration-300",
          collapsed && "lg:flex-col lg:gap-0"
        )}>
          <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-gradient-gold shadow-gold cursor-pointer hover:scale-110 transition-transform">
            <span className="text-sm font-bold text-white tracking-tighter">TR</span>
          </div>
          <div className={cn(
            "flex flex-col justify-center transition-all duration-300 overflow-hidden",
            collapsed ? "lg:w-0 lg:h-0 lg:opacity-0" : "w-auto opacity-100"
          )}>
            <p className="text-sm font-display font-bold text-sidebar-foreground leading-tight tracking-tight whitespace-nowrap">Temple Registry</p>
            <p className="text-[9px] text-sidebar-foreground/50 font-medium leading-tight uppercase tracking-wider whitespace-nowrap">Karnataka HR&amp;CE</p>
          </div>
        </div>
        
        {/* Collapse Toggle Button - Desktop Only, positioned absolutely when collapsed */}
        <button
          onClick={onToggleCollapse}
          className={cn(
            "hidden lg:flex items-center justify-center rounded-lg text-sidebar-foreground/60 hover:bg-sidebar-accent hover:text-sidebar-foreground transition-all group flex-shrink-0",
            collapsed ? "absolute -right-3 top-1/2 -translate-y-1/2 bg-sidebar border border-sidebar-border shadow-md h-6 w-6 z-50" : "p-1.5"
          )}
          title={collapsed ? "Expand sidebar" : "Collapse sidebar"}
        >
          {collapsed ? (
            <ChevronRight size={14} className="transition-transform group-hover:translate-x-0.5" />
          ) : (
            <ChevronLeft size={18} className="transition-transform group-hover:-translate-x-0.5" />
          )}
        </button>
      </div>

      {/* Navigation Section */}
      <nav className={cn(
        "flex-1 overflow-y-auto py-4 space-y-1.5 custom-scrollbar transition-all duration-300",
        collapsed ? "lg:px-2" : "px-4"
      )}>
        {navItems.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            className={({ isActive }) =>
              cn(
                'group flex items-center rounded-xl text-sm transition-all duration-200 relative',
                isActive
                  ? 'bg-sidebar-primary text-white font-semibold shadow-soft-md shadow-sidebar-primary/20'
                  : 'text-sidebar-foreground/70 hover:bg-sidebar-accent hover:text-sidebar-foreground',
                collapsed ? 'lg:justify-center lg:p-3' : 'gap-3 px-4 py-2.5',
              )
            }
            onClick={() => setOpen?.(false)}
            title={collapsed ? item.label : undefined}
          >
            {({ isActive }) => (
              <>
                <div className="transition-transform group-hover:scale-110 duration-200 flex items-center justify-center flex-shrink-0 w-5 h-5">
                  {item.icon}
                </div>
                <span className={cn(
                  "font-medium tracking-tight transition-all duration-300 overflow-hidden whitespace-nowrap",
                  collapsed ? "lg:w-0 lg:opacity-0 lg:absolute" : "w-auto opacity-100"
                )}>
                  {item.label}
                </span>
                {/* Pending badge */}
                {item.badge && !collapsed && (
                  <span className="ml-auto flex-shrink-0 min-w-[18px] h-[18px] rounded-full bg-destructive text-destructive-foreground text-[10px] font-bold flex items-center justify-center px-1 tabular-nums">
                    {item.badge > 99 ? '99+' : item.badge}
                  </span>
                )}
                {/* Active indicator dot */}
                <div className={cn(
                  "rounded-full bg-white transition-all duration-200 flex-shrink-0",
                  isActive ? "opacity-100" : "opacity-0",
                  collapsed ? "lg:absolute lg:bottom-1.5 lg:left-1/2 lg:-translate-x-1/2 lg:h-1 lg:w-1" : "ml-auto h-1.5 w-1.5"
                )} />
              </>
            )}
          </NavLink>
        ))}
      </nav>

      {/* User Info & Actions Section */}
      <div className={cn(
        "border-t border-sidebar-border/50 transition-all duration-300",
        collapsed ? "lg:p-2" : "px-3 py-2"
      )}>
        <Button
          variant="ghost"
          size="sm"
          className={cn(
            "w-full rounded-lg text-sidebar-foreground/50 hover:bg-destructive/10 hover:text-destructive transition-all group",
            collapsed ? "lg:justify-center lg:p-3" : "justify-start gap-3 px-3 py-2.5"
          )}
          onClick={handleLogout}
          title={collapsed ? "Sign Out" : undefined}
        >
          <LogOut size={15} className="transition-transform group-hover:scale-110 flex-shrink-0" />
          <span className={cn(
            "font-medium text-xs uppercase tracking-widest transition-all duration-300 overflow-hidden whitespace-nowrap",
            collapsed ? "lg:w-0 lg:opacity-0 lg:absolute" : "w-auto opacity-100"
          )}>
            Sign Out
          </span>
        </Button>
      </div>
    </aside>
  )
}
