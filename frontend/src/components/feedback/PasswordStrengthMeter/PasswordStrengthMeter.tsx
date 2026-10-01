const COLORS = ['bg-red-500', 'bg-orange-400', 'bg-yellow-400', 'bg-emerald-500']
const LABELS = ['Weak', 'Fair', 'Good', 'Strong']

/** Score (see getPasswordStrength) a password must reach before a gated submit button re-enables. */
export const MIN_ACCEPTABLE_PASSWORD_SCORE = 3

/** 0 = too short, 1 = Weak, 2 = Fair, 3 = Good, 4 = Strong. */
export function getPasswordStrength(password: string): number {
  if (password.length < 8) return 0
  let score = 1
  if (/[A-Z]/.test(password)) score++
  if (/[0-9]/.test(password)) score++
  if (/[^A-Za-z0-9]/.test(password)) score++
  return score
}

interface PasswordStrengthMeterProps {
  password: string
}

/** 4-segment strength bar + label. Renders nothing until the user starts typing. */
export function PasswordStrengthMeter({ password }: PasswordStrengthMeterProps) {
  if (password.length === 0) return null
  const score = getPasswordStrength(password)

  return (
    <div className="space-y-1 pt-1">
      <div className="flex gap-1 h-1.5">
        {[0, 1, 2, 3].map(i => (
          <div key={i} className={`flex-1 rounded-full transition-colors ${i < score ? COLORS[score - 1] : 'bg-muted'}`} />
        ))}
      </div>
      <p className={`text-xs font-medium ${score >= 3 ? 'text-emerald-600' : score === 2 ? 'text-yellow-600' : 'text-red-500'}`}>
        {LABELS[score - 1] ?? 'Too short'}
      </p>
    </div>
  )
}
