import { Phone, Shield, UserCircle, ChevronLeft, Clipboard, Clock } from 'lucide-react'
import { cn } from '@/lib/utils'
import { StatusBadge } from '@/components/data-display/StatusBadge/StatusBadge'
import { Button } from '@/components/ui/button'
import { ChatPanel } from '@/features/declaration/components/ChatPanel'
import type { BoardMemberSummary, DeclarationSummary } from '@/features/dc/dcTypes'
import type { DeclarationStatus } from '@/features/declaration/declarationTypes'

// ─── KPI Card ─────────────────────────────────────────────────────────────────

interface KpiCardProps {
  label: string
  value: string | number
  icon: React.ReactNode
  variant?: 'danger' | 'warning' | 'success' | 'neutral'
  note?: string
  className?: string
}

export function KpiCard({ label, value, icon, variant = 'neutral', note, className }: KpiCardProps) {
  const outerCls = {
    danger:  'bg-red-50/50 border-red-100 dark:bg-red-950/10 dark:border-red-900/20',
    warning: 'bg-amber-50/50 border-amber-100 dark:bg-amber-950/10 dark:border-amber-900/20',
    success: 'bg-emerald-50/50 border-emerald-100 dark:bg-emerald-950/10 dark:border-emerald-900/20',
    neutral: 'bg-white border-border',
  }
  const iconCls = {
    danger:  'text-red-600 bg-red-100/50',
    warning: 'text-amber-600 bg-amber-100/50',
    success: 'text-emerald-600 bg-emerald-100/50',
    neutral: 'text-primary bg-primary/10',
  }
  return (
    <div className={cn(
      'relative overflow-hidden rounded-xl border p-5 flex items-center gap-4 transition-all duration-200 hover:shadow-md hover:-translate-y-0.5 shadow-sm',
      outerCls[variant],
      className,
    )}>
      <div className={cn('size-11 rounded-xl flex items-center justify-center shrink-0', iconCls[variant])}>
        {icon}
      </div>
      <div className="min-w-0">
        <p className="text-xs font-medium text-muted-foreground/80 uppercase tracking-label">{label}</p>
        <div className="flex items-baseline gap-2 mt-1">
          <h3 className="text-base font-bold text-foreground tracking-title leading-tight truncate" title={String(value)}>{value}</h3>
          {note && <span className="text-xs text-muted-foreground font-regular break-words">({note})</span>}
        </div>
      </div>
    </div>
  )
}

// ─── Board Member Card ────────────────────────────────────────────────────────

const KEY_ROLES = ['chairperson', 'chairman', 'treasurer', 'president', 'secretary']

export function BoardMemberCard({ member }: { member: BoardMemberSummary }) {
  const isKey = KEY_ROLES.some((r) => member.designation?.toLowerCase().includes(r))
  return (
    <div className={cn(
      'rounded-xl border p-4 flex flex-col gap-3 transition-colors hover:bg-muted/5 bg-white',
      isKey ? 'border-primary/30 shadow-sm' : 'border-border'
    )}>
      <div className="flex items-start justify-between gap-4">
        <div className="flex items-center gap-3">
          <div className="size-9 rounded-lg bg-muted flex items-center justify-center shrink-0">
            <UserCircle size={20} className="text-muted-foreground/60" />
          </div>
          <div className="min-w-0">
            <p className="font-semibold text-sm text-foreground break-words">{member.fullName}</p>
            <p className="text-xs font-medium text-primary uppercase tracking-label">{member.designation}</p>
          </div>
        </div>
        {isKey && (
          <span className="text-xs font-medium uppercase tracking-label px-1.5 py-0.5 rounded bg-amber-50 text-amber-600 border border-amber-100">
            Key
          </span>
        )}
      </div>
      {(member.contactNumber || member.maskedAadhaar) && (
        <div className="pt-3 border-t border-border/50 space-y-2">
          {member.contactNumber && (
            <div className="flex items-center gap-2 text-xs text-muted-foreground font-regular">
              <Phone size={12} className="text-primary/70" />
              <span>{member.contactNumber}</span>
            </div>
          )}
          {member.maskedAadhaar && (
            <div className="flex items-center gap-2 text-xs text-muted-foreground font-mono font-regular">
              <Shield size={12} className="text-primary/70" />
              <span>{member.maskedAadhaar}</span>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// ─── Section Card ─────────────────────────────────────────────────────────────

interface SectionCardProps {
  title: string
  icon: React.ReactNode
  children: React.ReactNode
  className?: string
  action?: React.ReactNode
}

export function SectionCard({ title, icon, children, className, action }: SectionCardProps) {
  return (
    <section className={cn(
      'bg-card border border-border rounded-xl shadow-sm flex flex-col transition-all duration-200 hover:shadow-md hover:border-primary/20',
      className
    )}>
      <div className="px-5 py-4 border-b border-border/50 flex items-center justify-between bg-muted/5">
        <div className="flex items-center gap-3">
          <div className="text-primary/70">
            {icon}
          </div>
          <h2 className="text-md font-semibold text-foreground tracking-section">{title}</h2>
        </div>
        {action}
      </div>
      <div className="p-5 flex-1">
        {children}
      </div>
    </section>
  )
}

// ─── Detail Item ──────────────────────────────────────────────────────────────

export function DetailItem({ label, value }: { label: string; value?: string | number | null }) {
  return (
    <div className="space-y-2">
      <dt className="text-xs font-medium uppercase tracking-label text-muted-foreground/80">{label}</dt>
      <dd className="text-sm font-regular text-foreground leading-tight break-words">{value ?? '—'}</dd>
    </div>
  )
}

// ─── DeclarationCard ──────────────────────────────────────────────────────────

interface DeclarationCardProps {
  declaration: DeclarationSummary
  canAct: boolean
  isSelected: boolean
  onSelect: () => void
  onApprove: () => void
  onReject: () => void
  onClarify: () => void
  onFlagPhysical: () => void
}

export function DeclarationCard({
  declaration,
  canAct,
  isSelected,
  onSelect,
  onApprove,
  onReject,
  onClarify,
  onFlagPhysical,
}: DeclarationCardProps) {
  const actionable = ['SUBMITTED', 'UNDER_REVIEW', 'CLARIFICATION_RESPONDED', 'SITE_VISIT_SCHEDULED', 'SITE_VISIT_COMPLETED', 'VERIFIED'].includes(declaration.status)
  const isUrgent = declaration.status === 'OVERDUE'

  return (
    <div
      className={cn(
        'rounded-xl border bg-white transition-all overflow-hidden',
        isUrgent ? 'border-red-200 bg-red-50/10' : 'border-slate-200 shadow-sm',
        isSelected && 'ring-1 ring-primary border-primary shadow-md'
      )}
    >
      {/* Header row — clickable expand/collapse */}
      <div
        className="px-5 py-4 flex items-center gap-4 cursor-pointer relative group"
        onClick={onSelect}
        role="button"
        aria-expanded={isSelected}
      >
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-3">
            <span className="text-sm font-semibold text-foreground tracking-tight">FY {declaration.financialYear}</span>
            <span className="text-xs font-medium text-muted-foreground bg-muted px-1.5 py-0.5 rounded border border-border/50 uppercase tracking-label leading-none">
              v{declaration.versionNumber}
            </span>
            <StatusBadge status={declaration.status} className="scale-75 origin-left" />
          </div>
          {declaration.acknowledgementNumber && (
            <div className="flex items-center gap-1.5 mt-1.5">
              <Clipboard size={10} className="text-muted-foreground/50" />
              <p className="text-xs text-muted-foreground font-mono font-regular">
                ACK-{declaration.acknowledgementNumber}
              </p>
            </div>
          )}
        </div>
        <div className="text-right flex-shrink-0">
          <div className="text-xs font-medium text-muted-foreground uppercase tracking-label mb-0.5 leading-none">
            {declaration.submittedAt ? 'Filed on' : 'State'}
          </div>
          <p className="text-sm font-semibold text-foreground">
            {declaration.submittedAt
              ? new Date(declaration.submittedAt).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })
              : 'DRAFT'}
          </p>
          {actionable && !isSelected && (
            <div className="text-xs text-amber-600 font-medium flex items-center gap-1 justify-end mt-1 animate-pulse tracking-label">
              <Clock size={10} /> ACTION REQ.
            </div>
          )}
        </div>
        
        {/* Expansion indicator */}
        <div className={cn("ml-2 text-muted-foreground transition-transform duration-300 opacity-60", isSelected && "rotate-180")}>
           <ChevronLeft size={14} className="-rotate-90" />
        </div>
      </div>

      {/* RBAC-gated action bar */}
      {canAct && actionable && isSelected && (
        <div className="border-t border-border bg-muted/10 px-5 py-3 flex gap-2">
          <Button size="sm" onClick={onApprove} className="h-8 text-xs font-medium bg-primary hover:bg-primary/90 text-primary-foreground shadow-sm tracking-button">
            APPROVE
          </Button>
          <Button size="sm" variant="outline" onClick={onReject} className="h-8 text-xs font-medium text-destructive border-destructive/20 hover:bg-destructive/5 tracking-button">
            REJECT
          </Button>
          <Button size="sm" variant="outline" onClick={onClarify} className="h-8 text-xs font-medium border-border bg-white hover:bg-muted tracking-button">
            CLARIFY
          </Button>
          <Button size="sm" variant="outline" onClick={onFlagPhysical} className="h-8 text-xs font-medium border-border bg-white hover:bg-muted tracking-button">
            SITE VISIT
          </Button>
        </div>
      )}

      {/* Unified Chat Panel — replaces old clarification audit trail */}
      {isSelected && (
        <div className="border-t border-border px-5 py-4 bg-muted/5">
          <ChatPanel
            declarationId={declaration.id}
            declarationStatus={declaration.status as DeclarationStatus}
            readonly={true}
          />
        </div>
      )}
    </div>
  )
}
