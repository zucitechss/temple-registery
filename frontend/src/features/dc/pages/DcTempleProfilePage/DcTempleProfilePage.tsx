// imports
import { useState, useMemo, useEffect, useRef } from 'react'
import { useParams, useNavigate, useSearchParams } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { toast } from 'sonner'
import { extractApiErrorMessage } from '@/lib/apiError'
import {
  ChevronLeft, AlertTriangle, MapPin, FileText, Info, Shield, Users, Briefcase, Clock, IndianRupee
} from 'lucide-react'
import { cn } from '@/lib/utils'
import { CardSkeleton, Skeleton } from '@/components/feedback/Skeleton/Skeleton'
import { TempleGradeBadge } from '@/components/data-display/StatusBadge/TempleGradeBadge'
import { EmptyState } from '@/components/feedback/EmptyState/EmptyState'
import { ReadOnlyBanner } from '@/components/feedback/ReadOnlyBanner/ReadOnlyBanner'
import { ROUTE_PATHS } from '@/constants/routePaths'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from '@/components/ui/form'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { useAppSelector } from '@/app/store'
import { USER_ROLES } from '@/constants/roles'
import { usePermissions } from '@/features/access-control/hooks/usePermissions'
import { TARGET_KEYS } from '@/features/access-control/constants/targetKeys'
import {
  useDcTempleProfile,
  useDcDeclarationDetail,
  useWorkflowActions,
  useDcPendingProfileStaging,
  useProfileWorkflowActions,
} from '@/features/dc/dcHooks'
import {
  useVerifyTempleMutation,
  useFlagTempleMutation,
  useUnflagTempleMutation,
} from '@/features/dc/dcApi'
import {
  useApproveTrustMutation,
  useRejectTrustMutation,
  useSubmitTrustMutation,
} from '@/features/governance/governanceApi'
import {
  useCreateTrustMutation,
  useUpdateTrustMutation,
} from '@/features/trust/trustApi'
import { createTrustSchema, TRUST_TYPES, updateTrustSchema, type CreateTrustRequest, type UpdateTrustRequest } from '@/features/trust/trustTypes'
import {
  useCreateEmployeeMutation,
  useUpdateEmployeeMutation,
} from '@/features/employee/employeeApi'
import {
  useCreateContractorMutation,
  useUpdateContractorMutation,
} from '@/features/contractor/contractorApi'
import { createContractorSchema, ServiceType, PaymentStatus, SERVICE_TYPE_LABELS, PAYMENT_STATUS_LABELS } from '@/features/contractor/contractorTypes'
import type { CreateContractorRequest as ContractorFormValues } from '@/features/contractor/contractorTypes'
import type { ContractorResponse as DcContractorResponse } from '@/features/dc/dcTypes'
import {
  workflowApproveSchema,
  workflowRejectSchema,
  dcClarifySchema,
  type WorkflowApproveRequest,
  type WorkflowRejectRequest,
  type DcClarifyRequest,
} from '@/features/governance/governanceTypes'
import type { TempleGrade } from '@/features/temple-profile/hooks/templeTypes'
import type { CreateEmployeeRequest, UpdateEmployeeRequest } from '@/features/employee/employeeTypes'
import { EMPLOYEE_TYPES, EMPLOYEE_STATUSES } from '@/features/employee/employeeTypes'

import {
  OverviewTab,
  DeclarationsTab,
  TrustTab,
  StaffTab,
  ContractorsTab,
  DocumentsTab,
  TimelineTab,
} from './tabs'

/** Kollur Sri Mookambika Devi Temple — the only temple with a prepared static finance dashboard. */
const FINANCE_DASHBOARD_TEMPLE_ID = 300001
const FINANCE_DASHBOARD_SRC = '/dc/temple-300001-dashboard.html'

export function DcTempleProfilePage() {
  const { templeId } = useParams<{ templeId: string }>()
  const navigate = useNavigate()
  const id = Number(templeId)

  const role = useAppSelector((s) => s.auth.currentUser?.role)
  const currentTempleId = useAppSelector((s) => s.auth.currentUser?.templeId)
  const isTa = role === USER_ROLES.TEMPLE_AUTHORITY
  const isOwnTemple = isTa && currentTempleId === id
  const canAct =
    role === USER_ROLES.DISTRICT_COLLECTOR || role === USER_ROLES.SUPER_ADMIN
  const { can } = usePermissions()

  // Governance visibility: TEMPLE_AUTHORITY sees governance only for their own temple.
  // For DC/SA roles, only show governance when the temple has a pending profile submission.
  // This prevents showing an empty action panel on a newly-created temple with no staging data.

  // SA can edit any temple — DC cannot
  const canEdit = role === USER_ROLES.SUPER_ADMIN

  const { profile, isLoading, isError, isFetching: profileFetching, refetch: refetchProfile } = useDcTempleProfile(id)
  // TA cannot act on profile staging — skip the fetch to avoid unnecessary 404 noise
  const { pendingStaging, isFetching: stagingFetching, refetch: refetchPendingStaging } = useDcPendingProfileStaging(id, isTa)
  // isRefetchingProfile: true when a profile/staging governance action is in-flight and data is being refreshed
  const isRefetchingProfile = profileFetching || stagingFetching
  // Profile oversight panel depends on pending profile staging for DC/SA.
  // Trust oversight is independent and should be visible for all non-readonly viewers.
  const showProfileGovernance = isTa ? isOwnTemple : !!pendingStaging
  const showTrustGovernance = isTa ? isOwnTemple : true
  const { submitApproveProfile, submitRejectProfile } = useProfileWorkflowActions()

  const [selectedDeclarationId, setSelectedDeclarationId] = useState<number | null>(null)
  const [searchParams, setSearchParams] = useSearchParams()
  const [activeTab, setActiveTab] = useState(() => searchParams.get('tab') ?? 'overview')

  const { declaration: selectedDeclarationDetail } = useDcDeclarationDetail(selectedDeclarationId ?? 0)

  const {
    dialog,
    openDialog,
    closeDialog,
    confirmApprove,
    confirmReject,
    confirmClarify,
    confirmScheduleSiteVisit,
    confirmMarkUnderReview,
    isSubmitting,
  } = useWorkflowActions()

  const markedUnderReviewRef = useRef<Set<number>>(new Set())

  useEffect(() => {
    if (selectedDeclarationId && selectedDeclarationDetail) {
      if (
        (selectedDeclarationDetail.status === 'SUBMITTED' || selectedDeclarationDetail.status === 'CLARIFICATION_RESPONDED') &&
        !markedUnderReviewRef.current.has(selectedDeclarationId)
      ) {
        markedUnderReviewRef.current.add(selectedDeclarationId)
        confirmMarkUnderReview(selectedDeclarationId)
      }
    }
  }, [selectedDeclarationId, selectedDeclarationDetail?.status])

  const [verifyTemple] = useVerifyTempleMutation()
  const [flagTemple] = useFlagTempleMutation()
  const [unflagTemple, { isLoading: isUnflagging }] = useUnflagTempleMutation()
  const [approveTrust, { isLoading: verifyingTrust }] = useApproveTrustMutation()
  const [rejectTrust, { isLoading: rejectingTrust }] = useRejectTrustMutation()
  // isRefetchingTrust: true while trust is being approved/rejected AND profile data is refreshing
  const isRefetchingTrust = (verifyingTrust || rejectingTrust) || profileFetching

  const overdueDecls = useMemo(() =>
    profile?.declarations.filter((d) => d.status === 'OVERDUE') ?? [],
    [profile?.declarations]
  )
  const pendingReviewDecls = useMemo(() =>
    profile?.declarations.filter((d) => ['SUBMITTED', 'UNDER_REVIEW', 'CLARIFICATION_RESPONDED'].includes(d.status)) ?? [],
    [profile?.declarations]
  )
  const isOverdue = overdueDecls.length > 0
  const needsReview = pendingReviewDecls.length > 0

  const locationDisplay = useMemo(() =>
    [profile?.districtName, profile?.talukName, profile?.hobliName].filter(Boolean).join(' › '),
    [profile?.districtName, profile?.talukName, profile?.hobliName]
  )

  const verificationPosture = useMemo(() => {
    if (!profile) return null
    const { temple } = profile
    if (isOverdue || temple.verificationStatus === 'FLAGGED') {
      return { label: 'High Risk', color: 'bg-red-500/20 text-red-400 border-red-500/30' }
    }
    if (needsReview || temple.verificationStatus === 'UNVERIFIED') {
      return { label: 'Needs Attention', color: 'bg-amber-500/20 text-amber-400 border-amber-500/30' }
    }
    return { label: 'Verified', color: 'bg-emerald-500/20 text-emerald-400 border-emerald-500/30' }
  }, [profile, isOverdue, needsReview])

  // Module-level status summary removed — status is shown inside each tab, not in the header

  if (isLoading) {
    return (
      <div className="space-y-6 animate-in fade-in duration-500">
        <div className="flex items-center gap-4">
          <Skeleton className="h-10 w-10 rounded-xl" />
          <div className="space-y-2">
            <Skeleton className="h-6 w-48" />
            <Skeleton className="h-4 w-32" />
          </div>
        </div>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
          {Array.from({ length: 4 }).map((_, i) => <CardSkeleton key={i} />)}
        </div>
        <CardSkeleton />
      </div>
    )
  }

  if (isError || !profile) {
    return (
      <div className="flex flex-col items-center justify-center min-h-[60vh]">
        <EmptyState
          title="Temple not found"
          description="Unable to load temple profile. It may not exist or you may not have access."
          action={{ label: 'Go back', onClick: () => navigate(-1) }}
        />
      </div>
    )
  }

  const { temple, declarations, boardMembers, employees, contractors, trust } = profile
  const boardMemberCount = (boardMembers?.current?.length ?? 0) + (boardMembers?.past?.length ?? 0)

  return (
    <div className="animate-in fade-in duration-500">
      {/*(role === USER_ROLES.AUDITOR || role === USER_ROLES.VIEWER || (isTa && !isOwnTemple)) && (
        <ReadOnlyBanner message={
          isTa && !isOwnTemple
            ? 'Viewing in read-only mode. Use your TA dashboard to edit your own temple.'
            : 'You are viewing this temple profile in read-only mode. Verification, flagging, and approval actions are not available.'
        } />
      )*/}
      {/* Tabs must wrap TabsList — so we wrap the whole content including the header */}
      <Tabs value={activeTab} onValueChange={(tab) => { setActiveTab(tab); setSearchParams({ tab }, { replace: true }) }} className="flex flex-col bg-slate-50 min-h-screen">

        {/* ── HERO CASE HEADER ─────────────────────────────────────────────── */}
        <header 
          className="relative overflow-hidden mb-0 rounded-t-xl"
          style={{
            background: 'linear-gradient(135deg, hsl(36 80% 50%), hsl(24 85% 55%))',
            boxShadow: '0 4px 20px hsl(36 80% 50% / 0.25)'
          }}
        >
          {/* Decorative background elements - matching DC Dashboard */}
          <div className="absolute -right-8 -top-8 h-40 w-40 rounded-full bg-white/15 pointer-events-none" />
          <div className="absolute right-20 -bottom-10 h-28 w-28 rounded-full bg-white/10 pointer-events-none" />

          {/* Alert Banner */}
          {(isOverdue || needsReview) && (
            <div className={cn(
              'flex items-center justify-between gap-4 px-6 py-2.5 text-xs font-semibold uppercase tracking-wider backdrop-blur-sm',
              isOverdue ? 'bg-red-900/40 text-white border-b border-red-800/30' : 'bg-amber-900/30 text-white border-b border-amber-800/20',
            )} role="alert">
              <span className="flex items-center gap-2">
                <AlertTriangle size={14} aria-hidden />
                {isOverdue
                  ? `${overdueDecls.length} declaration${overdueDecls.length > 1 ? 's' : ''} overdue — immediate action required`
                  : `${pendingReviewDecls.length} declaration${pendingReviewDecls.length > 1 ? 's' : ''} awaiting review`}
              </span>
              {canAct && (
                <button
                  onClick={() => setActiveTab('declarations')}
                  className="bg-white/25 border border-white/30 px-4 py-1.5 rounded-lg hover:bg-white/40 transition-all font-semibold text-xs tracking-button shadow-sm"
                >
                  {isOverdue ? 'ACT NOW' : 'REVIEW'}
                </button>
              )}
            </div>
          )}

          {/* Main Header Content */}
          <div className="relative z-10 px-6 py-5">
            <div className="flex items-start justify-between gap-6">
              <div className="flex items-start gap-4 min-w-0">
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={() => navigate(-1)}
                  className="mt-1 h-10 w-10 shrink-0 rounded-lg bg-white/20 hover:bg-white/30 text-white border border-white/30 backdrop-blur-sm transition-all"
                >
                  <ChevronLeft size={18} />
                </Button>
                <div className="min-w-0">
                  <div className="flex items-center gap-3 flex-wrap">
                    <h1 className="text-xl font-bold text-white leading-tight">
                      {temple.name}
                    </h1>
                    {temple.grade && <TempleGradeBadge grade={temple.grade as TempleGrade} variant="on-dark" />}
                    {verificationPosture && (
                      <span className={cn(
                        "px-2.5 py-1 rounded-md text-xs font-semibold uppercase tracking-wider border backdrop-blur-sm",
                        verificationPosture.label === 'High Risk' 
                          ? 'bg-red-500/20 text-white border-red-400/30'
                          : verificationPosture.label === 'Needs Attention'
                          ? 'bg-amber-500/20 text-white border-amber-400/30'
                          : 'bg-emerald-500/20 text-white border-emerald-400/30'
                      )}>
                        {verificationPosture.label}
                      </span>
                    )}
                  </div>
                  <div className="mt-2 flex flex-wrap items-center gap-4 text-xs text-white/80 font-medium">
                    <div className="flex items-center gap-1.5">
                      <MapPin size={13} className="text-white/60" />
                      {locationDisplay || 'Location not set'}
                    </div>
                    {temple.registrationNumber && (
                      <div className="flex items-center gap-1.5 pl-4 border-l border-white/20">
                        <FileText size={13} className="text-white/60" />
                        Reg ID: <span className="text-white font-semibold">{temple.registrationNumber}</span>
                      </div>
                    )}
                  </div>
                </div>
              </div>

              <div className="flex items-center gap-3 shrink-0">
                {temple.verificationStatus === 'FLAGGED' && canAct && (
                  <Button
                    size="sm"
                    variant="outline"
                    className="border-white/30 text-white hover:bg-white/20 bg-transparent"
                    onClick={async () => {
                      try {
                        await unflagTemple({ id }).unwrap()
                        toast.success('Temple flag removed.')
                      } catch (err) {
                        toast.error(extractApiErrorMessage(err, 'Failed to remove flag. Please try again.'))
                      }
                    }}
                    disabled={isUnflagging}
                  >
                    {isUnflagging ? 'Removing flag…' : '🏳 Remove Flag'}
                  </Button>
                )}
              </div>
            </div>
          </div>

        </header>
        
        {/* Tab Navigation — Moved outside for sticky functionality */}
        <div 
          className="sticky top-0 z-30 shadow-lg backdrop-blur-sm"
          style={{
            background: 'rgba(30, 27, 24, 0.95)',
            borderTop: '1px solid rgba(255, 255, 255, 0.08)',
            borderBottom: '1px solid rgba(255, 255, 255, 0.08)'
          }}
        >
          <div className="overflow-x-auto overflow-y-hidden scrollbar-thin scrollbar-thumb-white/10 scrollbar-track-transparent px-6">
            <TabsList className="h-12 p-0 bg-transparent gap-1 flex w-full min-w-max">
              {(
                [
                  { v: 'overview',     label: 'Overview',      icon: <Info size={14} />,        count: null,                    targetKey: TARGET_KEYS.TAB_DC_TEMPLE_OVERVIEW },
                  { v: 'finance',      label: 'Finance Dashboard', icon: <IndianRupee size={14} />, count: null,               targetKey: TARGET_KEYS.TAB_DC_TEMPLE_FINANCE },
                  { v: 'declarations', label: 'Declarations',  icon: <FileText size={14} />,    count: declarations?.length ?? 0, targetKey: TARGET_KEYS.TAB_DC_TEMPLE_DECLARATIONS },
                  { v: 'trust',        label: 'Trust & Board', icon: <Shield size={14} />,      count: boardMemberCount,          targetKey: TARGET_KEYS.TAB_DC_TEMPLE_TRUST },
                  { v: 'staff',        label: 'Staff',         icon: <Users size={14} />,       count: employees?.length ?? 0,    targetKey: TARGET_KEYS.TAB_DC_TEMPLE_STAFF },
                  { v: 'contractors',  label: 'Contractors',   icon: <Briefcase size={14} />,   count: contractors?.length ?? 0,  targetKey: TARGET_KEYS.TAB_DC_TEMPLE_CONTRACTORS },
                  { v: 'documents',    label: 'Documents',     icon: <FileText size={14} />,    count: null,                    targetKey: TARGET_KEYS.TAB_DC_TEMPLE_DOCUMENTS },
                  { v: 'timeline',     label: 'Timeline',      icon: <Clock size={14} />,       count: null,                    targetKey: TARGET_KEYS.TAB_DC_TEMPLE_TIMELINE },
                ] as const
              ).filter((tab) => tab.v !== 'timeline' || showProfileGovernance || canAct)
              // Static oversight dashboard exists only for Kollur Sri Mookambika Devi Temple.
              .filter((tab) => tab.v !== 'finance' || id === FINANCE_DASHBOARD_TEMPLE_ID)
              .filter((tab) => can(tab.targetKey))
              .map((tab) => (
                <TabsTrigger
                  key={tab.v}
                  value={tab.v}
                  className={cn(
                    "relative h-12 flex items-center gap-2 px-4 text-xs font-semibold transition-all duration-200 tracking-wider whitespace-nowrap rounded-t-lg",
                    "text-slate-400 hover:text-white hover:bg-white/5",
                    "data-[state=active]:text-white shadow-none"
                  )}
                  style={activeTab === tab.v ? {
                    background: 'linear-gradient(to bottom, rgba(251, 146, 60, 0.15), rgba(249, 115, 22, 0.08))',
                    borderLeft: '1px solid rgba(251, 146, 60, 0.2)',
                    borderRight: '1px solid rgba(251, 146, 60, 0.2)',
                    borderTop: '1px solid rgba(251, 146, 60, 0.2)',
                  } : {}}
                >
                  {tab.icon}
                  <span className="hidden sm:inline">{tab.label}</span>
                  {tab.count !== null && tab.count > 0 && (
                    <span className={cn(
                      "ml-1.5 text-[10px] font-bold px-1.5 py-0.5 rounded-md transition-all",
                      activeTab === tab.v
                        ? "bg-orange-500 text-white shadow-sm"
                        : "bg-slate-800/80 text-slate-400 border border-slate-700/50"
                    )}>
                      {tab.count}
                    </span>
                  )}
                  {activeTab === tab.v && (
                    <div 
                      className="absolute bottom-0 inset-x-0 h-0.5"
                      style={{
                        background: 'linear-gradient(90deg, hsl(36 80% 50%), hsl(24 85% 55%))',
                        boxShadow: '0 0 12px rgba(251, 146, 60, 0.6)'
                      }}
                    />
                  )}
                </TabsTrigger>
              ))}
            </TabsList>
          </div>
        </div>

        {/* ── TAB CONTENTS ─────────────────────────────────────────────────── */}
        <div className="relative z-[1] mt-6">
          <TabsContent value="overview" className="mt-0 focus-visible:outline-none animate-in fade-in slide-in-from-bottom-4 duration-500">
            <OverviewTab
              profile={profile}
              canAct={canAct}
              showGovernance={showProfileGovernance}
              pendingStaging={pendingStaging}
              isRefetching={isRefetchingProfile}
              onApproveProfile={async (notes) => {
                if (!pendingStaging) return
                const success = await submitApproveProfile(pendingStaging.id, id, { remarks: notes })
                if (success) {
                  await refetchPendingStaging()
                  refetchProfile()
                }
              }}
              onRejectProfile={async (reason) => {
                if (!pendingStaging) return
                const success = await submitRejectProfile(pendingStaging.id, id, { reason })
                if (success) {
                  await refetchPendingStaging()
                  refetchProfile()
                }
              }}
              onVerifyTemple={async (notes) => {
                await verifyTemple({ id, body: { notes } }).unwrap()
              }}
              onFlagTemple={async (reason) => {
                await flagTemple({ id, body: { reason } }).unwrap()
              }}
              onEditProfile={
                isOwnTemple ? () => navigate(ROUTE_PATHS.TA_TEMPLE_EDIT)
                : canEdit ? () => navigate(ROUTE_PATHS.ADMIN_TEMPLE_EDIT.replace(':templeId', String(id)))
                : undefined
              }
            />
          </TabsContent>

          {can(TARGET_KEYS.TAB_DC_TEMPLE_DECLARATIONS) && (
          <TabsContent value="declarations" className="mt-0 focus-visible:outline-none animate-in fade-in slide-in-from-bottom-4 duration-500">
            <DeclarationsTab
              declarations={declarations}
              canAct={canAct}
              selectedDeclarationId={selectedDeclarationId}
              selectedDeclarationDetail={selectedDeclarationDetail}
              onSelectDeclaration={setSelectedDeclarationId}
              onApprove={(declarationId) => openDialog('approve', declarationId, id)}
              onReject={(declarationId) => openDialog('reject', declarationId, id)}
              onClarify={(declarationId) => openDialog('clarify', declarationId, id)}
              onFlagPhysical={(declarationId) => openDialog('schedule-site-visit', declarationId, id)}
              onManageDeclarations={
                canEdit ? () => navigate(ROUTE_PATHS.ADMIN_TEMPLE_DECLARATIONS.replace(':templeId', String(id))) : undefined
              }
            />
          </TabsContent>
          )}

          {can(TARGET_KEYS.TAB_DC_TEMPLE_TRUST) && (
          <TabsContent value="trust" className="mt-0 focus-visible:outline-none animate-in fade-in slide-in-from-bottom-4 duration-500">
            <TrustTab
              trust={trust}
              boardMembers={boardMembers}
              trustFinancials={profile.trustFinancials}
              boardMeetings={profile.boardMeetings ?? []}
              canAct={canAct}
              showGovernance={showTrustGovernance}
              isRefetching={isRefetchingTrust}
              onEditTrust={
                isOwnTemple && trust ? () => navigate(ROUTE_PATHS.TA_TRUST)
                : canEdit && trust ? () => navigate(ROUTE_PATHS.ADMIN_TEMPLE_TRUST.replace(':templeId', String(id)))
                : undefined
              }
              onCreateTrust={
                canEdit && !trust ? () => navigate(ROUTE_PATHS.ADMIN_TEMPLE_TRUST.replace(':templeId', String(id))) : undefined
              }
              onVerifyTrust={async (trustId, _notes) => {
                try {
                  await approveTrust(trustId).unwrap()
                  toast.success('Trust approved.')
                  refetchProfile()
                } catch (err) {
                  toast.error(extractApiErrorMessage(err, 'Failed to approve trust. Please try again.'))
                }
              }}
              onRejectTrust={async (trustId, reason) => {
                try {
                  await rejectTrust({ trustId, body: { reason } }).unwrap()
                  toast.success('Trust rejected.')
                  refetchProfile()
                } catch (err) {
                  toast.error(extractApiErrorMessage(err, 'Failed to reject trust. Please try again.'))
                }
              }}
            />
          </TabsContent>
          )}

          {can(TARGET_KEYS.TAB_DC_TEMPLE_STAFF) && (
          <TabsContent value="staff" className="mt-0 focus-visible:outline-none animate-in fade-in slide-in-from-bottom-4 duration-500">
            <StaffTab
              employees={employees}
              onAddEmployee={
                isOwnTemple ? () => navigate(ROUTE_PATHS.TA_EMPLOYEES)
                : canEdit ? () => navigate(ROUTE_PATHS.ADMIN_TEMPLE_EMPLOYEES.replace(':templeId', String(id)) + '?from=staff')
                : undefined
              }
              onEditEmployee={
                isOwnTemple ? () => navigate(ROUTE_PATHS.TA_EMPLOYEES)
                : canEdit ? () => navigate(ROUTE_PATHS.ADMIN_TEMPLE_EMPLOYEES.replace(':templeId', String(id)) + '?from=staff')
                : undefined
              }
            />
          </TabsContent>
          )}

          {can(TARGET_KEYS.TAB_DC_TEMPLE_CONTRACTORS) && (
          <TabsContent value="contractors" className="mt-0 focus-visible:outline-none animate-in fade-in slide-in-from-bottom-4 duration-500">
            <ContractorsTab
              contractors={contractors}
              onAddContractor={
                isOwnTemple ? () => navigate(ROUTE_PATHS.TA_CONTRACTORS)
                : canEdit ? () => navigate(ROUTE_PATHS.ADMIN_TEMPLE_CONTRACTORS.replace(':templeId', String(id)) + '?from=contractors')
                : undefined
              }
              onEditContractor={
                isOwnTemple ? () => navigate(ROUTE_PATHS.TA_CONTRACTORS)
                : canEdit ? () => navigate(ROUTE_PATHS.ADMIN_TEMPLE_CONTRACTORS.replace(':templeId', String(id)) + '?from=contractors')
                : undefined
              }
            />
          </TabsContent>
          )}

          {can(TARGET_KEYS.TAB_DC_TEMPLE_DOCUMENTS) && (
          <TabsContent value="documents" className="mt-0 focus-visible:outline-none animate-in fade-in slide-in-from-bottom-4 duration-500">
            <DocumentsTab templeId={id} />
          </TabsContent>
          )}

          {can(TARGET_KEYS.TAB_DC_TEMPLE_TIMELINE) && (
          <TabsContent value="timeline" className="mt-0 focus-visible:outline-none animate-in fade-in slide-in-from-bottom-4 duration-500">
            {(showProfileGovernance || canAct)
              ? <TimelineTab templeId={id} />
              : null
            }
          </TabsContent>
          )}

          {id === FINANCE_DASHBOARD_TEMPLE_ID && can(TARGET_KEYS.TAB_DC_TEMPLE_FINANCE) && (
          <TabsContent value="finance" className="mt-0 focus-visible:outline-none animate-in fade-in slide-in-from-bottom-4 duration-500">
            {/* Full-bleeed: the static page carries its own 24px gutter, matching the tab bar's px-6. */}
            <iframe
              src={FINANCE_DASHBOARD_SRC}
              title="Finance Dashboard — Kollur Sri Mookambika Devi Temple"
              className="block w-full border-0 bg-[#161513] h-[calc(100vh-7rem)]"
            />
          </TabsContent>
          )}
        </div>
      </Tabs>

      {/* ── WORKFLOW DIALOGS ─────────────────────────────────────────────── */}
      <WorkflowDialog
        open={dialog.open && dialog.kind === 'approve'}
        kind="approve"
        onClose={closeDialog}
        onSubmit={confirmApprove}
        isSubmitting={isSubmitting}
      />
      <WorkflowDialog
        open={dialog.open && dialog.kind === 'reject'}
        kind="reject"
        onClose={closeDialog}
        onSubmit={confirmReject}
        isSubmitting={isSubmitting}
      />
      <WorkflowDialog
        open={dialog.open && dialog.kind === 'clarify'}
        kind="clarify"
        onClose={closeDialog}
        onSubmit={confirmClarify}
        isSubmitting={isSubmitting}
      />
      <WorkflowDialog
        open={dialog.open && dialog.kind === 'schedule-site-visit'}
        kind="schedule-site-visit"
        onClose={closeDialog}
        onSubmit={confirmScheduleSiteVisit}
        isSubmitting={isSubmitting}
      />


    </div>
  )
}

type DialogKind = 'approve' | 'reject' | 'clarify' | 'schedule-site-visit'
type DialogFormPayload = WorkflowApproveRequest | WorkflowRejectRequest | DcClarifyRequest

interface WorkflowDialogProps {
  open: boolean
  kind: DialogKind
  onClose: () => void
  onSubmit: (payload: any) => Promise<void>
  isSubmitting: boolean
}

type DialogField = 'remarks' | 'message' | 'notes'
type DialogValues = { remarks: string; message: string; notes: string }

const DIALOG_META: Record<DialogKind, { title: string; description: string; field: DialogField; label: string; placeholder: string; schema: any }> = {
  approve: {
    title: 'Approve Declaration',
    description: 'This will transition the declaration to APPROVED and generate an acknowledgement number.',
    field: 'remarks',
    label: 'Notes (optional)',
    placeholder: 'Internal notes for this approval…',
    schema: workflowApproveSchema,
  },
  reject: {
    title: 'Reject Declaration',
    description: 'Rejection is irreversible. The declaration status will be permanently set to REJECTED.',
    field: 'remarks',
    label: 'Rejection remarks',
    placeholder: 'Provide the reason for rejection (min 10 characters)…',
    schema: workflowRejectSchema,
  },
  clarify: {
    title: 'Request Clarification',
    description: 'The temple authority will be notified to respond to the clarification.',
    field: 'message',
    label: 'Clarification message',
    placeholder: 'Describe what needs clarification (min 10 characters)…',
    schema: dcClarifySchema,
  },
  'schedule-site-visit': {
    title: 'Schedule Site Visit',
    description: 'Schedule a physical site visit for this declaration.',
    field: 'notes',
    label: 'Site visit notes',
    placeholder: 'Describe the reason for the site visit…',
    schema: workflowApproveSchema,
  },
}

function WorkflowDialog({ open, kind, onClose, onSubmit, isSubmitting }: WorkflowDialogProps) {
  const meta = DIALOG_META[kind]
  const form = useForm<Partial<DialogValues>>({
    resolver: zodResolver(meta.schema),
    defaultValues: { [meta.field]: '' } as Partial<DialogValues>,
  })

  const handleSubmit = form.handleSubmit(async (values) => {
    await onSubmit(values as DialogFormPayload)
    form.reset()
  })

  return (
    <AlertDialog open={open} onOpenChange={(o) => !o && onClose()}>
      <AlertDialogContent className="rounded-2xl border-border/50 shadow-soft-lg animate-in zoom-in-95 duration-300">
        <AlertDialogHeader>
          <AlertDialogTitle className="text-lg font-display font-bold">{meta.title}</AlertDialogTitle>
          <AlertDialogDescription className="text-sm text-muted-foreground">{meta.description}</AlertDialogDescription>
        </AlertDialogHeader>
        {kind === 'reject' && (
          <div className="flex items-start gap-2.5 rounded-xl border border-destructive/20 bg-destructive/5 px-4 py-3 text-xs text-destructive" role="alert">
            <AlertTriangle size={14} className="shrink-0 mt-0.5" aria-hidden />
            <p className="font-medium">This action is irreversible. Rejecting will require the temple authority to submit a new declaration.</p>
          </div>
        )}
        <Form {...form}>
          <form onSubmit={handleSubmit} className="space-y-4">
            <FormField
              control={form.control}
              name={meta.field}
              render={({ field }) => (
                <FormItem>
                  <FormLabel className="text-[11px] font-bold uppercase tracking-wider text-muted-foreground">{meta.label}</FormLabel>
                  <FormControl>
                    <Textarea {...field} placeholder={meta.placeholder} className="rounded-xl border-border/50 bg-card/50" rows={3} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <AlertDialogFooter>
              <AlertDialogCancel onClick={onClose} type="button" className="rounded-xl">Cancel</AlertDialogCancel>
              {/* Do NOT use AlertDialogAction here — it intercepts the click and
                  closes the dialog before the form onSubmit fires. Plain Button only. */}
              <Button
                type="submit"
                disabled={isSubmitting}
                className="rounded-xl font-bold px-6 bg-gradient-gold hover:scale-105 transition-transform"
              >
                {isSubmitting ? 'Submitting…' : 'Confirm Action'}
              </Button>
            </AlertDialogFooter>
          </form>
        </Form>
      </AlertDialogContent>
    </AlertDialog>
  )
}

// ── SA Profile Edit Dialog removed — SA now uses SaTempleEditPage ────────────

// ── SA Employee Dialog ────────────────────────────────────────────────────────

// ── SA Contractor Dialog ──────────────────────────────────────────────────────

interface SaContractorDialogProps {
  open: boolean
  templeId: number
  contractor: DcContractorResponse | null
  onClose: () => void
  onSaved: () => void
  createContractor: (args: { templeId: number; body: ContractorFormValues }) => Promise<any>
  updateContractor: (args: { id: number; body: Partial<ContractorFormValues> }) => Promise<any>
  isSaving: boolean
}

function SaContractorDialog({
  open, templeId, contractor, onClose, onSaved, createContractor, updateContractor, isSaving,
}: SaContractorDialogProps) {
  const isEdit = contractor !== null
  const form = useForm<ContractorFormValues>({
    resolver: zodResolver(createContractorSchema),
    defaultValues: {
      companyName: '',
      gstNumber: '',
      serviceType: ServiceType.CIVIL_WORKS,
      contractReference: '',
      workOrderDate: '',
      contractStartDate: '',
      contractEndDate: '',
      contractValue: 0,
      paymentStatus: PaymentStatus.PENDING,
    },
  })

  useEffect(() => {
    if (open) {
      if (contractor) {
        form.reset({
          companyName: contractor.companyName ?? '',
          gstNumber: contractor.gstNumber ?? '',
          serviceType: (contractor.serviceType as ServiceType) ?? ServiceType.CIVIL_WORKS,
          contractReference: contractor.contractReference ?? '',
          workOrderDate: contractor.workOrderDate ?? '',
          contractStartDate: contractor.contractStartDate ?? '',
          contractEndDate: contractor.contractEndDate ?? '',
          contractValue: contractor.contractValue ?? 0,
          paymentStatus: (contractor.paymentStatus as PaymentStatus) ?? PaymentStatus.PENDING,
        })
      } else {
        form.reset({
          companyName: '',
          gstNumber: '',
          serviceType: ServiceType.CIVIL_WORKS,
          contractReference: '',
          workOrderDate: '',
          contractStartDate: '',
          contractEndDate: '',
          contractValue: 0,
          paymentStatus: PaymentStatus.PENDING,
        })
      }
    }
  }, [open]) // eslint-disable-line react-hooks/exhaustive-deps

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      const body = { ...values }
      if (!body.gstNumber) delete body.gstNumber
      if (!body.workOrderDate) delete body.workOrderDate
      if (!body.contractEndDate) delete body.contractEndDate
      if (isEdit && contractor) {
        await updateContractor({ id: contractor.id, body })
      } else {
        await createContractor({ templeId, body: body as ContractorFormValues })
      }
      toast.success(isEdit ? 'Contractor updated successfully.' : 'Contractor added successfully.')
      onSaved()
    } catch (err) {
      toast.error(extractApiErrorMessage(err, 'Failed to save contractor. Please try again.'))
    }
  })

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-2xl max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{isEdit ? 'Edit Contractor' : 'Add Contractor'}</DialogTitle>
        </DialogHeader>
        <Form {...form}>
          <form onSubmit={onSubmit} className="space-y-4">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <FormField control={form.control} name="companyName" render={({ field }) => (
                <FormItem className="sm:col-span-2"><FormLabel>Company Name *</FormLabel><FormControl><Input {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="serviceType" render={({ field }) => (
                <FormItem><FormLabel>Service Type *</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue placeholder="Select type" /></SelectTrigger></FormControl>
                    <SelectContent>
                      {Object.values(ServiceType).map((t) => (
                        <SelectItem key={t} value={t}>{SERVICE_TYPE_LABELS[t]}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="gstNumber" render={({ field }) => (
                <FormItem><FormLabel>GST Number</FormLabel><FormControl><Input className="uppercase" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="contractReference" render={({ field }) => (
                <FormItem><FormLabel>Contract Reference *</FormLabel><FormControl><Input {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="contractValue" render={({ field }) => (
                <FormItem><FormLabel>Contract Value (₹) *</FormLabel>
                  <FormControl>
                    <Input type="number" min={0} step="0.01"
                      value={field.value ?? ''}
                      onChange={(e) => field.onChange(e.target.value === '' ? 0 : Number(e.target.value))} />
                  </FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="paymentStatus" render={({ field }) => (
                <FormItem><FormLabel>Payment Status *</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue placeholder="Select status" /></SelectTrigger></FormControl>
                    <SelectContent>
                      {Object.values(PaymentStatus).map((s) => (
                        <SelectItem key={s} value={s}>{PAYMENT_STATUS_LABELS[s]}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="contractStartDate" render={({ field }) => (
                <FormItem><FormLabel>Contract Start Date *</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="contractEndDate" render={({ field }) => (
                <FormItem><FormLabel>Contract End Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="workOrderDate" render={({ field }) => (
                <FormItem><FormLabel>Work Order Date</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={onClose}>Cancel</Button>
              <Button type="submit" disabled={isSaving}>
                {isSaving ? 'Saving…' : isEdit ? 'Update Contractor' : 'Add Contractor'}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  )
}

// ── SA Trust Edit Dialog ──────────────────────────────────────────────────────

interface SaTrustEditDialogProps {
  open: boolean
  templeId: number
  trust: import('@/features/dc/dcTypes').DcTrustSummary | null
  onClose: () => void
  onSaved: () => Promise<void>
  createTrust: (args: any) => any
  updateTrust: (args: any) => any
  submitTrust: (trustId: number) => any
  isSaving: boolean
}

function SaTrustEditDialog({
  open, templeId, trust, onClose, onSaved, createTrust, updateTrust, submitTrust, isSaving,
}: SaTrustEditDialogProps) {
  const isEdit = trust !== null

  const buildFormValues = (currentTrust: import('@/features/dc/dcTypes').DcTrustSummary | null) => ({
    trustName: currentTrust?.trustName ?? '',
    trustType: (currentTrust?.trustType as CreateTrustRequest['trustType']) ?? 'MULTI_TRUSTEE',
    registrationNumber: currentTrust?.registrationNumber ?? '',
    registeringAuthority: currentTrust?.registeringAuthority ?? '',
    dateOfRegistration: currentTrust?.dateOfRegistration ?? '',
    panNumber: '',
    bankAccountNumber: '',
    bankName: currentTrust?.bankName ?? '',
    bankBranch: currentTrust?.bankBranch ?? '',
    annualIncome: currentTrust?.annualIncome ?? null,
  })

  const form = useForm<CreateTrustRequest | UpdateTrustRequest>({
    resolver: zodResolver(isEdit ? updateTrustSchema : createTrustSchema),
    defaultValues: {
      ...buildFormValues(trust),
    },
  })

  useEffect(() => {
    if (open) {
      form.reset(buildFormValues(trust))
    }
  }, [open, trust])

  const onSubmit = form.handleSubmit(async (values: CreateTrustRequest | UpdateTrustRequest) => {
    try {
      if (isEdit && trust) {
        const body: Partial<UpdateTrustRequest> = { ...(values as UpdateTrustRequest) }
        if (!body.panNumber) delete body.panNumber
        if (!body.bankAccountNumber) delete body.bankAccountNumber
        await updateTrust({ trustId: trust.id, body }).unwrap()
        await submitTrust(trust.id).unwrap()
        toast.success('Trust updated and submitted for review.')
      } else {
        const created = await createTrust({ templeId, body: values as CreateTrustRequest }).unwrap()
        const createdTrustId = created?.data?.id
        if (!createdTrustId) {
          throw new Error('Trust created but trust id was missing in response.')
        }
        await submitTrust(createdTrustId).unwrap()
        toast.success('Trust created and submitted for review.')
      }
      await onSaved()
    } catch (err) {
      toast.error(extractApiErrorMessage(err, isEdit
        ? 'Failed to update trust. Please try again.'
        : 'Failed to create trust. Please try again.'))
    }
  })

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-2xl max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{isEdit ? 'Edit Trust Registration (SA)' : 'Create Trust Registration (SA)'}</DialogTitle>
        </DialogHeader>
        <Form {...form}>
          <form onSubmit={onSubmit} className="space-y-4">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <FormField control={form.control} name="trustName" render={({ field }) => (
                <FormItem><FormLabel>Trust Name *</FormLabel><FormControl><Input {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="trustType" render={({ field }) => (
                <FormItem><FormLabel>Trust Type *</FormLabel>
                  <Select onValueChange={field.onChange} value={field.value}>
                    <FormControl><SelectTrigger><SelectValue placeholder="Select type" /></SelectTrigger></FormControl>
                    <SelectContent>
                      {TRUST_TYPES.map((type) => (
                        <SelectItem key={type} value={type}>{type.replace(/_/g, ' ')}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="registrationNumber" render={({ field }) => (
                <FormItem><FormLabel>Registration Number *</FormLabel><FormControl><Input {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="registeringAuthority" render={({ field }) => (
                <FormItem><FormLabel>Registering Authority *</FormLabel><FormControl><Input {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="dateOfRegistration" render={({ field }) => (
                <FormItem><FormLabel>Date of Registration *</FormLabel><FormControl><Input type="date" {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="panNumber" render={({ field }) => (
                <FormItem><FormLabel>PAN Number</FormLabel>
                  <FormControl><Input {...field} className="uppercase" placeholder={trust?.panNumberMasked ?? (isEdit ? 'Leave blank to keep existing' : 'ABCDE1234F')} /></FormControl>
                  <FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="bankAccountNumber" render={({ field }) => (
                <FormItem><FormLabel>Bank Account Number</FormLabel>
                  <FormControl><Input inputMode="numeric" {...field} placeholder={trust?.bankAccountMasked ?? (isEdit ? 'Leave blank to keep existing' : 'Enter account number')} /></FormControl>
                  <FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="bankName" render={({ field }) => (
                <FormItem><FormLabel>Bank Name *</FormLabel><FormControl><Input {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="bankBranch" render={({ field }) => (
                <FormItem><FormLabel>Bank Branch *</FormLabel><FormControl><Input {...field} /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="annualIncome" render={({ field }) => (
                <FormItem><FormLabel>Annual Income</FormLabel>
                  <FormControl>
                    <Input type="number" min={0} step="0.01"
                      value={field.value ?? ''}
                      onChange={(e) => field.onChange(e.target.value === '' ? null : Number(e.target.value))} />
                  </FormControl><FormMessage /></FormItem>
              )} />
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={onClose}>Cancel</Button>
              <Button type="submit" disabled={isSaving}>
                {isSaving ? 'Saving…' : isEdit ? 'Save & Submit for Review' : 'Create & Submit for Review'}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  )
}

const saEmployeeSchema = z.object({
  fullName: z.string().min(2, 'Required').max(200),
  employeeType: z.enum(EMPLOYEE_TYPES),
  designation: z.string().max(150).optional().or(z.literal('')),
  mobile: z.string().regex(/^[6-9]\d{9}$/, 'Must be 10 digits').optional().or(z.literal('')),
  dateOfJoining: z.string().optional().or(z.literal('')),
  status: z.enum(EMPLOYEE_STATUSES),
})
type SaEmployeeValues = z.infer<typeof saEmployeeSchema>

interface SaEmployeeDialogProps {
  open: boolean
  templeId: number
  employeeId: number | null
  onClose: () => void
  onSaved: () => void
  createEmployee: (args: any) => Promise<any>
  updateEmployee: (args: any) => Promise<any>
  isSaving: boolean
}

function SaEmployeeDialog({
  open, templeId, employeeId, onClose, onSaved,
  createEmployee, updateEmployee, isSaving,
}: SaEmployeeDialogProps) {
  const isEditing = employeeId !== null

  const form = useForm<SaEmployeeValues>({
    resolver: zodResolver(saEmployeeSchema),
    defaultValues: {
      fullName: '',
      employeeType: 'ADMINISTRATIVE',
      designation: '',
      mobile: '',
      dateOfJoining: '',
      status: 'ACTIVE',
    },
  })

  useEffect(() => {
    if (open && !isEditing) {
      form.reset({ fullName: '', employeeType: 'ADMINISTRATIVE', designation: '', mobile: '', dateOfJoining: '', status: 'ACTIVE' })
    }
  }, [open, isEditing]) // eslint-disable-line react-hooks/exhaustive-deps

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      if (isEditing) {
        const body: UpdateEmployeeRequest = {
          fullName: values.fullName,
          employeeType: values.employeeType,
          designation: values.designation || undefined,
          mobile: values.mobile || undefined,
          dateOfJoining: values.dateOfJoining || undefined,
          status: values.status,
        }
        await updateEmployee({ id: employeeId!, body })
        toast.success('Employee updated.')
      } else {
        const body: CreateEmployeeRequest = {
          fullName: values.fullName,
          employeeType: values.employeeType,
          isHereditary: false,
          designation: values.designation || undefined,
          mobile: values.mobile || undefined,
          dateOfJoining: values.dateOfJoining || undefined,
        }
        await createEmployee({ templeId, body })
        toast.success('Employee added.')
      }
      onSaved()
    } catch (err) {
      toast.error(extractApiErrorMessage(err, 'Failed to save employee. Please try again.'))
    }
  })

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{isEditing ? 'Edit Employee' : 'Add Employee'} (SA)</DialogTitle>
        </DialogHeader>
        <Form {...form}>
          <form onSubmit={onSubmit} className="space-y-4">
            <FormField control={form.control} name="fullName" render={({ field }) => (
              <FormItem><FormLabel>Full Name</FormLabel><FormControl><Input {...field} /></FormControl><FormMessage /></FormItem>
            )} />
            <div className="grid grid-cols-2 gap-4">
              <FormField control={form.control} name="employeeType" render={({ field }) => (
                <FormItem>
                  <FormLabel>Type</FormLabel>
                  <FormControl>
                    <select {...field} className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm">
                      {EMPLOYEE_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                    </select>
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )} />
              {isEditing && (
                <FormField control={form.control} name="status" render={({ field }) => (
                  <FormItem>
                    <FormLabel>Status</FormLabel>
                    <FormControl>
                      <select {...field} className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm">
                        {EMPLOYEE_STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
                      </select>
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )} />
              )}
            </div>
            <FormField control={form.control} name="designation" render={({ field }) => (
              <FormItem><FormLabel>Designation</FormLabel><FormControl><Input {...field} /></FormControl><FormMessage /></FormItem>
            )} />
            <div className="grid grid-cols-2 gap-4">
              <FormField control={form.control} name="mobile" render={({ field }) => (
                <FormItem><FormLabel>Mobile</FormLabel><FormControl><Input {...field} inputMode="numeric" /></FormControl><FormMessage /></FormItem>
              )} />
              <FormField control={form.control} name="dateOfJoining" render={({ field }) => (
                <FormItem><FormLabel>Date of Joining</FormLabel><FormControl><Input {...field} type="date" /></FormControl><FormMessage /></FormItem>
              )} />
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={onClose}>Cancel</Button>
              <Button type="submit" disabled={isSaving}>{isSaving ? 'Saving…' : isEditing ? 'Update' : 'Add Employee'}</Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  )
}

