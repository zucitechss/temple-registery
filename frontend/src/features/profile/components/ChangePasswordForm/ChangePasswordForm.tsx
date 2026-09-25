import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Eye, EyeOff, Loader2 } from 'lucide-react'
import { toast } from 'sonner'
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { extractApiErrorMessage } from '@/lib/apiError'
import { useChangePasswordMutation } from '@/features/auth/authApi'
import { useLogout } from '@/features/auth/authHooks'
import { changePasswordSchema, type ChangePasswordRequest } from '@/features/auth/authTypes'

interface ChangePasswordFormProps {
  /** Called after the password has been changed successfully, before the sign-out. */
  onSuccess?: () => void
  /** Rendered next to the submit button — e.g. a Cancel button inside a dialog. */
  secondaryAction?: React.ReactNode
  submitLabel?: string
}

interface PasswordFieldProps {
  label: string
  placeholder: string
  autoComplete: string
  description?: string
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  field: any
}

/** Password input with a show/hide toggle, matching the existing LoginForm treatment. */
function PasswordField({ label, placeholder, autoComplete, description, field }: PasswordFieldProps) {
  const [visible, setVisible] = useState(false)

  return (
    <FormItem className="space-y-1.5">
      <FormLabel>{label}</FormLabel>
      {/* FormControl must wrap the input itself — wrapping the div would put the generated
          id on a non-labellable element and break the label association. */}
      <div className="relative">
        <FormControl>
          <Input
            type={visible ? 'text' : 'password'}
            placeholder={placeholder}
            autoComplete={autoComplete}
            className="pr-10"
            {...field}
          />
        </FormControl>
        <button
          type="button"
          onClick={() => setVisible((v) => !v)}
          className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground transition-colors"
          aria-label={visible ? `Hide ${label.toLowerCase()}` : `Show ${label.toLowerCase()}`}
          tabIndex={-1}
        >
          {visible ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
        </button>
      </div>
      {description && <FormDescription>{description}</FormDescription>}
      <FormMessage />
    </FormItem>
  )
}

export function ChangePasswordForm({
  onSuccess,
  secondaryAction,
  submitLabel = 'Change Password',
}: ChangePasswordFormProps) {
  const [changePassword, { isLoading }] = useChangePasswordMutation()
  const { handleLogout } = useLogout()

  const form = useForm<ChangePasswordRequest>({
    resolver: zodResolver(changePasswordSchema),
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
  })

  const onSubmit = async (values: ChangePasswordRequest) => {
    try {
      const res = await changePassword(values).unwrap()
      if (!res.success) {
        toast.error(res.message || 'Could not change your password. Please try again.')
        return
      }
      form.reset()
      onSuccess?.()
      // The backend revokes every refresh token on a password change, and the access token
      // still carries the pre-change claims. Sign out so the next session is issued cleanly.
      toast.success('Your password has been changed successfully. Please sign in again.')
      await handleLogout()
    } catch (err) {
      const message = extractApiErrorMessage(err, 'Could not change your password. Please try again.')
      // Surface a wrong current password on the field it belongs to, not just as a toast.
      if (/current password/i.test(message)) {
        form.setError('currentPassword', { message })
      } else {
        toast.error(message)
      }
    }
  }

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
        <FormField
          control={form.control}
          name="currentPassword"
          render={({ field }) => (
            <PasswordField
              label="Current Password"
              placeholder="Enter your current password"
              autoComplete="current-password"
              field={field}
            />
          )}
        />

        <FormField
          control={form.control}
          name="newPassword"
          render={({ field }) => (
            <PasswordField
              label="New Password"
              placeholder="Enter a new password"
              autoComplete="new-password"
              description="At least 8 characters, and different from your current password."
              field={field}
            />
          )}
        />

        <FormField
          control={form.control}
          name="confirmPassword"
          render={({ field }) => (
            <PasswordField
              label="Confirm New Password"
              placeholder="Re-enter the new password"
              autoComplete="new-password"
              field={field}
            />
          )}
        />

        <div className="flex flex-col-reverse gap-2 pt-2 sm:flex-row sm:justify-end">
          {secondaryAction}
          <Button type="submit" disabled={isLoading} className="gap-2">
            {isLoading && <Loader2 className="h-4 w-4 animate-spin" />}
            {isLoading ? 'Saving…' : submitLabel}
          </Button>
        </div>
      </form>
    </Form>
  )
}
