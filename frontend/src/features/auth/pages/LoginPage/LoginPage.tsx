import { LoginForm } from '../../components/LoginForm/LoginForm'

export function LoginPage() {
  return (
    <div className="flex h-screen overflow-hidden">
      {/* Left Side - Temple Image */}
      <div className="hidden lg:block lg:w-1/2 relative">
        {/* Temple Background Image */}
        <div 
          className="absolute inset-0 bg-cover bg-center"
          style={{
            backgroundImage: `url('https://images.unsplash.com/photo-1582510003544-4d00b7f74220?q=80&w=2070')`,
          }}
        >
          {/* Gradient Overlay */}
          <div className="absolute inset-0 bg-gradient-to-br from-orange-900/70 via-orange-800/60 to-amber-900/70" />
        </div>

        {/* Content Overlay */}
        <div className="relative z-10 flex flex-col justify-center h-full px-16 text-white">
          <div className="space-y-4">
            <h1 className="text-5xl font-bold leading-tight drop-shadow-lg">
              Temple Registry<br />Portal
            </h1>
            <div className="h-1 w-24 bg-amber-400 rounded-full" />
            <p className="text-xl text-orange-100 font-medium">
              Government of Karnataka
            </p>
            <p className="text-lg text-orange-200/90">
              Hindu Religious & Charitable<br />Endowments Department
            </p>
          </div>
        </div>
      </div>

      {/* Right Side - Login Form */}
      <div className="flex-1 flex items-center justify-center p-8 bg-white">
        <div className="w-full max-w-md">
          {/* Mobile Header */}
          <div className="lg:hidden mb-6 text-center">
            <h1 className="text-2xl font-bold text-gray-900 mb-2">Temple Registry Portal</h1>
            <p className="text-sm text-gray-600">Government of Karnataka — HR&CE</p>
          </div>

          {/* Login Card */}
          <div className="bg-gray-50 rounded-xl shadow-lg border border-gray-200 p-6">
            {/* Header inside card */}
            <div className="mb-6 text-center">
              <h2 className="text-2xl font-bold text-gray-900 mb-1">Welcome Back</h2>
              <p className="text-sm text-gray-600">Sign in to your account</p>
            </div>

            <LoginForm />
          </div>

          {/* Footer */}
          <p className="mt-4 text-center text-xs text-gray-500">
            © {new Date().getFullYear()} Government of Karnataka. All rights reserved.
          </p>
        </div>
      </div>
    </div>
  )
}
