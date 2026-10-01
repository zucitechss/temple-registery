import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { ArrowLeft, Eye, EyeOff } from 'lucide-react'
import { toast } from 'sonner'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { extractApiErrorMessage } from '@/lib/apiError'
import { ROUTE_PATHS } from '@/constants/routePaths'
import { usePasswordResetConfirmMutation } from '../../authApi'
import { passwordResetConfirmSchema, type PasswordResetConfirmRequest } from '../../authTypes'
import { AuthCardLayout } from '../../components/AuthCardLayout/AuthCardLayout'

const backToLogin = (
  <Link
    to={ROUTE_PATHS.LOGIN}
    className="mt-4 flex items-center justify-center gap-1.5 text-xs font-medium text-orange-600 hover:text-orange-700 hover:underline"
  >
    <ArrowLeft className="h-3.5 w-3.5" />
    Back to sign in
  </Link>
)

/**
 * Step 2 of the password reset flow: /reset-password?token=...
 *
 * Token validity, expiry and single use are all decided by the backend — this page only
 * forwards the token and renders whatever the server says.
 */
export function ResetPasswordPage() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const token = searchParams.get('token') ?? ''
  const [confirmReset, { isLoading }] = usePasswordResetConfirmMutation()
  const [showPassword, setShowPassword] = useState(false)

  const form = useForm<PasswordResetConfirmRequest>({
    resolver: zodResolver(passwordResetConfirmSchema),
    defaultValues: { token, newPassword: '', confirmPassword: '' },
  })

  const onSubmit = async (values: PasswordResetConfirmRequest) => {
    try {
      const res = await confirmReset(values).unwrap()
      if (!res.success) {
        toast.error(res.message || 'This password reset link is no longer valid.')
        return
      }
      toast.success('Your password has been reset successfully.')
      navigate(ROUTE_PATHS.LOGIN)
    } catch (err) {
      toast.error(extractApiErrorMessage(err, 'This password reset link is no longer valid.'))
    }
  }

  if (!token) {
    return (
      <AuthCardLayout title="Invalid Reset Link" footer={backToLogin}>
        <p className="text-center text-sm text-gray-700">
          This password reset link is not valid. Please request a new one.
        </p>
        <Button asChild variant="outline" className="mt-4 w-full">
          <Link to={ROUTE_PATHS.FORGOT_PASSWORD}>Request a new link</Link>
        </Button>
      </AuthCardLayout>
    )
  }

  return (
    <AuthCardLayout
      title="Reset Password"
      subtitle="Choose a new password for your account"
      footer={backToLogin}
    >
      <Form {...form}>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
          <FormField
            control={form.control}
            name="newPassword"
            render={({ field }) => (
              <FormItem className="space-y-1.5">
                <FormLabel className="text-gray-700 font-medium text-sm">New Password</FormLabel>
                {/* FormControl wraps the input, not the positioning div, so the generated id
                    lands on a labellable element. */}
                <div className="relative">
                  <FormControl>
                    <Input
                      type={showPassword ? 'text' : 'password'}
                      placeholder="Enter a new password"
                      autoComplete="new-password"
                      className="h-10 border-gray-300 focus:border-orange-500 focus:ring-2 focus:ring-orange-500/20 bg-white pr-10"
                      {...field}
                    />
                  </FormControl>
                  <button
                    type="button"
                    onClick={() => setShowPassword((v) => !v)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-700 transition-colors"
                    aria-label={showPassword ? 'Hide password' : 'Show password'}
                    tabIndex={-1}
                  >
                    {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </button>
                </div>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="confirmPassword"
            render={({ field }) => (
              <FormItem className="space-y-1.5">
                <FormLabel className="text-gray-700 font-medium text-sm">
                  Confirm New Password
                </FormLabel>
                <FormControl>
                  <Input
                    type="password"
                    placeholder="Re-enter the new password"
                    autoComplete="new-password"
                    className="h-10 border-gray-300 focus:border-orange-500 focus:ring-2 focus:ring-orange-500/20 bg-white"
                    {...field}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <p className="rounded-md bg-gray-100 p-3 text-xs text-gray-600">
            Your password must be at least 8 characters long.
          </p>

          <Button
            type="submit"
            className="w-full h-10 bg-gradient-to-r from-orange-500 to-amber-600 hover:from-orange-600 hover:to-amber-700 text-white font-semibold shadow-md hover:shadow-lg transition-all"
            disabled={isLoading}
          >
            {isLoading ? 'Resetting…' : 'Reset Password'}
          </Button>
        </form>
      </Form>
    </AuthCardLayout>
  )
}
