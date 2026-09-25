import type { ReactNode } from 'react'

interface AuthCardLayoutProps {
  title: string
  subtitle?: string
  children: ReactNode
  footer?: ReactNode
}

/**
 * The split-screen shell used by the login page, reused by the forgot/reset password pages so
 * they read as part of the same flow. Kept deliberately close to LoginPage's markup —
 * LoginPage itself is untouched.
 */
export function AuthCardLayout({ title, subtitle, children, footer }: AuthCardLayoutProps) {
  return (
    <div className="flex min-h-screen overflow-hidden">
      {/* Left Side - Temple Image */}
      <div className="hidden lg:block lg:w-1/2 relative">
        <div
          className="absolute inset-0 bg-cover bg-center"
          style={{
            backgroundImage: `url('https://images.unsplash.com/photo-1582510003544-4d00b7f74220?q=80&w=2070')`,
          }}
        >
          <div className="absolute inset-0 bg-gradient-to-br from-orange-900/70 via-orange-800/60 to-amber-900/70" />
        </div>

        <div className="relative z-10 flex flex-col justify-center h-full px-16 text-white">
          <div className="space-y-4">
            <h1 className="text-5xl font-bold leading-tight drop-shadow-lg">
              Temple Registry<br />Portal
            </h1>
            <div className="h-1 w-24 bg-amber-400 rounded-full" />
            <p className="text-xl text-orange-100 font-medium">Government of Karnataka</p>
            <p className="text-lg text-orange-200/90">
              Hindu Religious &amp; Charitable<br />Endowments Department
            </p>
          </div>
        </div>
      </div>

      {/* Right Side - Form */}
      <div className="flex-1 flex items-center justify-center p-8 bg-white">
        <div className="w-full max-w-md">
          <div className="lg:hidden mb-6 text-center">
            <h1 className="text-2xl font-bold text-gray-900 mb-2">Temple Registry Portal</h1>
            <p className="text-sm text-gray-600">Government of Karnataka — HR&amp;CE</p>
          </div>

          <div className="bg-gray-50 rounded-xl shadow-lg border border-gray-200 p-6">
            <div className="mb-6 text-center">
              <h2 className="text-2xl font-bold text-gray-900 mb-1">{title}</h2>
              {subtitle && <p className="text-sm text-gray-600">{subtitle}</p>}
            </div>

            {children}
          </div>

          {footer}

          <p className="mt-4 text-center text-xs text-gray-500">
            © 2024 Government of Karnataka. All rights reserved.
          </p>
        </div>
      </div>
    </div>
  )
}
