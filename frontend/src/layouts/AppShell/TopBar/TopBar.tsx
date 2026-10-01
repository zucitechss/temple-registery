import { Menu } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useAppSelector } from '@/app/store'
import { NotificationBell } from '@/features/notification/components/NotificationBell'
import { ROUTE_PATHS } from '@/constants/routePaths'

interface TopBarProps {
  title?: string
  onMenuClick?: () => void
}

export function TopBar({ title, onMenuClick }: TopBarProps) {
  const currentUser = useAppSelector((s) => s.auth.currentUser)
  
  // Don't show redundant title for dashboard
  const displayTitle = title === 'Dashboard' ? '' : title

  return (
    <header className="sticky top-0 z-20 flex h-16 items-center justify-between border-b border-border bg-card/60 backdrop-blur-xl px-4 sm:px-6">
      {/* Hamburger for mobile/tablet */}
      <div className="flex items-center gap-2">
        <button
          className="lg:hidden mr-2 rounded-md p-2 hover:bg-muted focus:outline-none focus:ring-2 focus:ring-primary transition-colors"
          aria-label="Open sidebar"
          onClick={onMenuClick}
        >
          <Menu size={22} />
        </button>
        {displayTitle && (
          <h1 className="text-lg font-semibold text-foreground truncate max-w-[180px] sm:max-w-none animate-in fade-in slide-in-from-left-2 duration-300">
            {displayTitle}
          </h1>
        )}
      </div>
      <div className="flex items-center gap-3">
        {/* Notification bell with dropdown */}
        <NotificationBell />

        {/* User profile / Avatar — opens the profile page */}
        {currentUser && (
          <Link
            to={ROUTE_PATHS.PROFILE}
            aria-label="View your profile"
            title="View your profile"
            className="flex items-center gap-3 pl-2 border-l border-border ml-1 rounded-md focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            <div className="hidden sm:block text-right">
              <p className="text-xs font-semibold text-foreground leading-none">{currentUser.fullName}</p>
              <p className="text-[10px] text-muted-foreground leading-none mt-1">{currentUser.role.replace(/_/g, ' ')}</p>
            </div>
            <div className="flex h-8 w-8 items-center justify-center rounded-full bg-gradient-gold shadow-gold text-xs font-bold text-white cursor-pointer hover:scale-105 transition-transform">
              {currentUser.fullName.charAt(0).toUpperCase()}
            </div>
          </Link>
        )}
      </div>
    </header>
  )
}
