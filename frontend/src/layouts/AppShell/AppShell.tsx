import { Outlet, useLocation } from 'react-router-dom'
import { useState, useEffect } from 'react'
import { Sidebar } from './Sidebar/Sidebar'
import { TopBar } from './TopBar/TopBar'

const PAGE_TITLES: Record<string, string> = {
  '/dc/dashboard': 'Dashboard',
  '/dc/notices': 'Notice Board',
  '/dc/temples': 'Temple Search',
  '/dc/declarations': 'Declarations',
  '/dc/export': 'Export Reports',
  '/dc/activity': 'Activity',
  '/ta/dashboard': 'Dashboard',
  '/ta/temple': 'Temple Profile',
  '/ta/temples': 'Temples',
  '/ta/trust': 'Trust & Board',
  '/ta/employees': 'Employees',
  '/ta/contractors': 'Contractors',
  '/ta/declarations': 'Declarations',
  '/ta/documents': 'Documents',
  '/ta/profile-status': 'Profile Status',
  '/ta/activity': 'Activity',
  '/admin/dashboard': 'Admin Dashboard',
  '/admin/users': 'User Management',
  '/admin/audit': 'Audit Logs',
  '/admin/geo': 'Geo Master',
  '/admin/tools': 'Admin Tools',
  '/admin/temple-governance': 'Temple Governance',
  '/admin/system-config': 'System Configuration',
  '/admin/notification-rules': 'Notification Rules',
  '/admin/notices': 'Notice Board',
  '/auditor/dashboard': 'Dashboard',
  '/auditor/temples': 'Temples',
  '/auditor/declarations': 'Declarations',
  '/auditor/observations': 'Observations',
  '/auditor/compliance': 'Compliance Report',
  '/auditor/audit-trail': 'Audit Trail',
  '/viewer/dashboard': 'Dashboard',
  '/viewer/temples': 'Temple Search',
  '/viewer/declarations': 'Declarations',
  '/viewer/compliance': 'Compliance Report',
  '/viewer/audit-trail': 'Audit Trail',
  '/viewer/export': 'Export Reports',
  '/notifications': 'Notifications',
  '/notifications/preferences': 'Notification Preferences',
  '/profile': 'My Profile',
}

export function AppShell() {
  const { pathname } = useLocation()
  const title = PAGE_TITLES[pathname] ?? 'Temple Registry'
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)

  // Load collapsed state from localStorage on mount
  useEffect(() => {
    const saved = localStorage.getItem('sidebarCollapsed')
    if (saved !== null) {
      setSidebarCollapsed(saved === 'true')
    }
  }, [])

  // Save collapsed state to localStorage
  const toggleSidebarCollapsed = () => {
    setSidebarCollapsed((prev) => {
      const newValue = !prev
      localStorage.setItem('sidebarCollapsed', String(newValue))
      return newValue
    })
  }

  return (
    <div className="flex h-screen overflow-hidden bg-background">
      {/* Sidebar for desktop/tablet, drawer for mobile */}
      <Sidebar 
        open={sidebarOpen} 
        setOpen={setSidebarOpen}
        collapsed={sidebarCollapsed}
        onToggleCollapse={toggleSidebarCollapsed}
      />
      {/* Overlay for mobile drawer */}
      {sidebarOpen && (
        <div
          className="fixed inset-0 z-30 bg-black/40 lg:hidden"
          onClick={() => setSidebarOpen(false)}
        />
      )}
      <div className="flex flex-1 flex-col overflow-hidden relative">
        <TopBar title={title} onMenuClick={() => setSidebarOpen((v) => !v)} />
        <main className="flex-1 overflow-y-auto w-full bg-background/50">
          <div className="max-w-7xl mx-auto px-4 py-6 sm:px-6 sm:py-8 md:px-8 md:py-10">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  )
}
