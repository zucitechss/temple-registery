import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { ArrowLeft, MailCheck } from 'lucide-react'
import { toast } from 'sonner'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { extractApiErrorMessage } from '@/lib/apiError'
import { ROUTE_PATHS } from '@/constants/routePaths'
import { usePasswordResetRequestMutation } from '../../authApi'
import { passwordResetRequestSchema, type PasswordResetRequest } from '../../authTypes'
import { AuthCardLayout } from '../../components/AuthCardLayout/AuthCardLayout'

/**
 * Step 1 of the password reset flow.
 *
 * The response is deliberately identical whether or not the address is registered — the
 * backend never reveals account existence, and neither does this screen.
 */
export function ForgotPasswordPage() {
  const [requestReset, { isLoading }] = usePasswordResetRequestMutation()
  const [submitted, setSubmitted] = useState(false)

  const form = useForm<PasswordResetRequest>({
    resolver: zodResolver(passwordResetRequestSchema),
    defaultValues: { email: '' },
  })

  const onSubmit = async (values: PasswordResetRequest) => {
    try {
      await requestReset(values).unwrap()
      setSubmitted(true)
    } catch (err) {
      // Rate limiting (429) is the only error a user should ever see here.
      toast.error(extractApiErrorMessage(err, 'Could not send the reset email. Please try again.'))
    }
  }

  const backToLogin = (
    <Link
      to={ROUTE_PATHS.LOGIN}
      className="mt-4 flex items-center justify-center gap-1.5 text-xs font-medium text-orange-600 hover:text-orange-700 hover:underline"
    >
      <ArrowLeft className="h-3.5 w-3.5" />
      Back to sign in
    </Link>
  )

  if (submitted) {
    return (
      <AuthCardLayout title="Check Your Email" footer={backToLogin}>
        <div className="flex flex-col items-center gap-3 text-center">
          <div className="rounded-full bg-green-100 p-3">
            <MailCheck className="h-6 w-6 text-green-700" />
          </div>
          <p className="text-sm text-gray-700">
            If an account exists for the provided information, password reset instructions have
            been sent.
          </p>
          <p className="text-xs text-gray-500">
            The link expires in 30 minutes and can only be used once. Remember to check your spam
            folder.
          </p>
        </div>
      </AuthCardLayout>
    )
  }

  return (
    <AuthCardLayout
      title="Forgot Password"
      subtitle="Enter your registered email and we'll send you a reset link"
      footer={backToLogin}
    >
      <Form {...form}>
        {/* noValidate: let the Zod message render in the app's own styling instead of the
            browser's native validation bubble for type="email". */}
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4" noValidate>
          <FormField
            control={form.control}
            name="email"
            render={({ field }) => (
              <FormItem className="space-y-1.5">
                <FormLabel className="text-gray-700 font-medium text-sm">Email</FormLabel>
                <FormControl>
                  <Input
                    type="email"
                    placeholder="Enter your registered email"
                    autoComplete="email"
                    className="h-10 border-gray-300 focus:border-orange-500 focus:ring-2 focus:ring-orange-500/20 bg-white"
                    {...field}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <Button
            type="submit"
            className="w-full h-10 bg-gradient-to-r from-orange-500 to-amber-600 hover:from-orange-600 hover:to-amber-700 text-white font-semibold shadow-md hover:shadow-lg transition-all mt-5"
            disabled={isLoading}
          >
            {isLoading ? 'Sending…' : 'Send Reset Link'}
          </Button>
        </form>
      </Form>
    </AuthCardLayout>
  )
}
