import { Building2, MapPin, Phone, Shield, TrendingUp, UserCircle, Info, Clock, CreditCard, Globe, BookOpen, ChevronDown, ChevronUp } from 'lucide-react'
import { useMemo, useState } from 'react'
import { SectionCard, DetailItem, KpiCard } from '../components'
import { GovernanceActionPanel } from '@/features/dc/components/GovernanceActionPanel/GovernanceActionPanel'
import { ProfileGovernanceSectionSkeleton } from '@/features/dc/components/DcSkeletons/DcSkeletons'
import { Button } from '@/components/ui/button'
import { formatList, formatCurrency } from '../utils'
import type { TempleFullProfileResponse, ProfileStagingResponse, ProfileCurrentResponse } from '@/features/dc/dcTypes'
import { DcTempleImageGallery } from '@/features/dc/components/DcTempleImageGallery'

interface OverviewTabProps {
  profile: TempleFullProfileResponse
  canAct: boolean
  /** Whether the caller is permitted to view governance/oversight metadata. False for TA viewing other temples. */
  showGovernance?: boolean
  pendingStaging?: ProfileStagingResponse | null
  onApproveProfile?: (notes: string) => Promise<void>
  onRejectProfile?: (reason: string) => Promise<void>
  onVerifyTemple: (notes: string) => Promise<void>
  onFlagTemple: (reason: string) => Promise<void>
  onEditProfile?: () => void
  /** True while profile/staging data is refetching after a governance action — replaces stale governance panel with skeleton. */
  isRefetching?: boolean
}

export function OverviewTab({
  profile,
  canAct,
  showGovernance = true,
  pendingStaging,
  onApproveProfile,
  onRejectProfile,
  onVerifyTemple,
  onFlagTemple,
  onEditProfile,
  isRefetching = false,
}: OverviewTabProps) {
  const { temple, trust, declarations, hobliName, talukName, districtName, cityName } = profile
  const currentProfile = profile.currentProfile

  const pendingGovernance = pendingStaging?.governanceStatus
  const pendingAllowedActions = pendingGovernance?.allowedActions ?? []

  // Data visibility: show pending staging data whenever the workflow is in a DC-review state,
  // regardless of whether action buttons are shown (e.g. UNDER_REVIEW shows data but may not
  // expose APPROVE/REJECT until the DC explicitly triggers those actions).
  const hasPendingData = pendingStaging !== null
    && pendingStaging !== undefined
    && ['SUBMITTED', 'UNDER_REVIEW', 'RESUBMITTED'].includes(pendingStaging.status)
  const displayPendingStaging = hasPendingData ? pendingStaging : null

  // Action visibility: approve/reject buttons only appear when the backend explicitly
  // returns those actions as allowed (sourced from TransitionRuleRegistry).
  const hasDcProfileAction = pendingAllowedActions.includes('APPROVE')
    || pendingAllowedActions.includes('RE_APPROVE')
    || pendingAllowedActions.includes('REJECT')
  const actionablePendingStaging = (hasDcProfileAction && hasPendingData) ? pendingStaging : null

  // Effective display values — 2-layer priority for profile-managed fields:
  //   1. Pending staging (TA submitted, awaiting or under DC review) — shows most current TA data
  //   2. Approved currentProfile — last approved snapshot from temple_profile_current
  //   No fallback to temple.* for these fields: temples holds identity data, not the
  //   profile-managed contact/bank set. Falling back to temple would show stale pre-approval
  //   data after a rejection or re-submission cycle.
  const effectiveContactName = displayPendingStaging?.contactPersonName || currentProfile?.contactPersonName || null
  const effectiveContactDesignation = displayPendingStaging?.contactPersonDesignation || currentProfile?.contactPersonDesignation || null
  const effectivePhone = displayPendingStaging?.phone || currentProfile?.phone || null
  const effectiveEmail = displayPendingStaging?.email || currentProfile?.email || null
  const effectiveWebsite = displayPendingStaging?.website || currentProfile?.website || temple.website || null
  const effectivePhotoUrl = displayPendingStaging?.photoUrl || currentProfile?.photoUrl || temple.photoUrl
  const effectiveLanguages = displayPendingStaging?.languagesOfWorship || currentProfile?.languagesOfWorship || temple.languagesOfWorship
  // Bank details — pendingStaging uses bankAccountNumberMasked, currentProfile uses bankAccountMasked
  const effectiveBankName = displayPendingStaging?.bankName || currentProfile?.bankName || null
  const effectiveBankIfsc = displayPendingStaging?.bankIfsc || currentProfile?.bankIfsc || null
  const effectiveBankAccountMasked = displayPendingStaging?.bankAccountNumberMasked || currentProfile?.bankAccountMasked || null
  // Profile content fields — previously missing from DC view
  const effectiveDescription = displayPendingStaging?.description || currentProfile?.description || null
  const effectiveLandmark = displayPendingStaging?.landmark || currentProfile?.landmark || temple.landmark || null
  const effectiveHistoricalSignificance = displayPendingStaging?.historicalSignificance || currentProfile?.historicalSignificance || temple.historicalSignificance || null
  const effectiveAnnualFestivals = displayPendingStaging?.annualFestivals || currentProfile?.annualFestivals || temple.annualFestivals || null
  const effectiveLinkedInstitutions = displayPendingStaging?.linkedInstitutions || currentProfile?.linkedInstitutions || temple.linkedInstitutions || null
  // Whether any profile field is sourced from a pending (unapproved) staging record
  const hasUnreviewedData = !!(displayPendingStaging && (
    displayPendingStaging.contactPersonName || displayPendingStaging.phone ||
    displayPendingStaging.email || displayPendingStaging.languagesOfWorship ||
    displayPendingStaging.bankName || displayPendingStaging.description ||
    displayPendingStaging.landmark || displayPendingStaging.website ||
    displayPendingStaging.historicalSignificance || displayPendingStaging.annualFestivals ||
    displayPendingStaging.linkedInstitutions ||
    displayPendingStaging.primaryDeity || displayPendingStaging.aliasName ||
    displayPendingStaging.grade || displayPendingStaging.tradition ||
    displayPendingStaging.addressLine1 || displayPendingStaging.pinCode ||
    displayPendingStaging.yearEstablished
  ))
  // Effective identity field values — show pending staging proposal when in review
  const effectivePrimaryDeity = displayPendingStaging?.primaryDeity || temple.primaryDeity || null
  const effectiveAliasName = displayPendingStaging?.aliasName || temple.aliasName || null
  const effectiveGrade = displayPendingStaging?.grade || temple.grade || null
  const effectiveTradition = displayPendingStaging?.tradition || temple.tradition || null
  const effectiveYearEstablished = displayPendingStaging?.yearEstablished ?? temple.yearEstablished ?? null
  const effectiveAddressLine1 = displayPendingStaging?.addressLine1 || temple.street || null
  const effectivePinCode = displayPendingStaging?.pinCode || temple.pinCode || null

  // Government-administered structures (Endowment boards, Devaswom boards) are
  // distinguished from privately-managed trusts for display purposes.
  const GOVERNMENT_TRUST_TYPES = ['ENDOWMENT', 'DEVASWOM']
  const trustStatusLabel = !trust
    ? 'Individual'
    : GOVERNMENT_TRUST_TYPES.includes(trust.trustType ?? '')
      ? 'Government'
      : 'Trust'

  const pendingReviewDecls = useMemo(() =>
    declarations.filter((d) => ['SUBMITTED', 'UNDER_REVIEW', 'CLARIFICATION_RESPONDED'].includes(d.status)),
    [declarations]
  )
  const overdueDecls = useMemo(() =>
    declarations.filter((d) => d.status === 'OVERDUE'),
    [declarations]
  )

  const effectiveLatitude = displayPendingStaging?.latitude ?? temple.latitude ?? null
  const effectiveLongitude = displayPendingStaging?.longitude ?? temple.longitude ?? null

  const mapsEmbedUrl = useMemo(() =>
    (effectiveLatitude && effectiveLongitude)
      ? `https://www.google.com/maps?q=${effectiveLatitude},${effectiveLongitude}&output=embed`
      : null,
    [effectiveLatitude, effectiveLongitude]
  )

  return (
    <div className="space-y-3 pb-6 max-w-[1600px] mx-auto">
      {/* KPI Snapshot */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
        <KpiCard
          label="Pending Review"
          value={pendingReviewDecls.length}
          icon={<Clock size={18} />}
          variant={pendingReviewDecls.length > 0 ? 'warning' : 'neutral'}
          className="shadow-md hover:shadow-lg transition-all duration-300 hover:-translate-y-1"
        />
        <KpiCard
          label="Overdue"
          value={overdueDecls.length}
          icon={<TrendingUp size={18} />}
          variant={overdueDecls.length > 0 ? 'danger' : 'neutral'}
          className="shadow-md hover:shadow-lg transition-all duration-300 hover:-translate-y-1"
        />
        <KpiCard
          label="Incomes Tracked"
          value={formatCurrency(trust?.annualIncome, true)}
          icon={<TrendingUp size={18} />}
          variant={trust?.annualIncome != null ? 'success' : 'neutral'}
          className="shadow-md hover:shadow-lg transition-all duration-300 hover:-translate-y-1"
        />
        <KpiCard
          label="Managed By"
          value={trustStatusLabel}
          icon={<Shield size={18} />}
          variant={trust ? 'success' : 'warning'}
          className="shadow-md hover:shadow-lg transition-all duration-300 hover:-translate-y-1"
        />
      </div>

      {/* Unified Content Container */}
      <div className="space-y-4">
        {/* Temple Identity Section - FULL WIDTH */}
        <div className="bg-white rounded-xl border border-slate-200/60 shadow-md overflow-hidden hover:shadow-lg transition-all duration-300">
          <div className="px-4 py-2.5 border-b border-slate-100 bg-gradient-to-r from-orange-50 to-amber-50 flex items-center gap-2.5">
            <div className="size-8 rounded-lg bg-gradient-to-br from-orange-500 to-orange-600 flex items-center justify-center shadow-md">
              <Building2 size={16} className="text-white" />
            </div>
            <h2 className="text-xs font-bold text-slate-900 uppercase tracking-wider">Temple Identity & Information</h2>
            {hasUnreviewedData && (
              <span className="ml-auto text-[9px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full bg-amber-100 text-amber-700 border border-amber-300">
                Pending Review
              </span>
            )}
            {onEditProfile && (
              <Button
                variant="outline"
                size="sm"
                onClick={onEditProfile}
                className="ml-auto text-xs h-7 px-3"
              >
                Edit Profile
              </Button>
            )}
          </div>
          
          <div className="p-3">
            <div className="grid grid-cols-1 lg:grid-cols-4 gap-4">
              {/* Left Side - Details (3/4 width) */}
              <div className="lg:col-span-3">
                <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-x-4 gap-y-2.5">
                  <DetailItem label="Primary Deity" value={effectivePrimaryDeity || '—'} />
                  <DetailItem label="Tradition" value={effectiveTradition || '—'} />
                  <DetailItem label="Year Established" value={effectiveYearEstablished ? effectiveYearEstablished : '—'} />
                  <DetailItem label="Registration No." value={temple.registrationNumber || '—'} />
                  <DetailItem label="Alias Name" value={effectiveAliasName || '—'} />
                  <DetailItem label="Languages" value={formatList(effectiveLanguages)} />
                  <DetailItem label="Grade" value={effectiveGrade ? `Grade ${effectiveGrade}` : '—'} />
                  <DetailItem label="Address" value={effectiveAddressLine1 || '—'} />
                  <DetailItem label="PIN Code" value={effectivePinCode || '—'} />
                </div>
              </div>

              {/* Right Side - Photo (1/4 width) */}
              <div className="lg:col-span-1 flex flex-col items-center justify-start gap-2">
                <div className="w-full aspect-square rounded-xl bg-gradient-to-br from-orange-50 to-amber-50 flex items-center justify-center overflow-hidden border-2 border-orange-200/60 shadow-lg group transition-all hover:shadow-xl hover:scale-105 duration-300">
                  {effectivePhotoUrl ? (
                    <img
                      src={`${import.meta.env.VITE_BASE_URL ?? ''}${effectivePhotoUrl}`}
                      alt={temple.name || 'Temple Photo'}
                      className="object-cover w-full h-full group-hover:scale-110 transition-transform duration-500"
                      onError={e => { (e.target as HTMLImageElement).style.display = 'none' }}
                    />
                  ) : (
                    <Building2 size={48} className="text-orange-300 group-hover:text-orange-400 transition-colors" />
                  )}
                </div>
                <span className="text-[8px] font-bold uppercase tracking-widest text-slate-400">Primary Photo</span>
              </div>
            </div>
          </div>
        </div>

        {/* 2-COLUMN LAYOUT for smaller cards */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {/* Location & Jurisdiction */}
          <div className="bg-white rounded-xl border border-slate-200/60 shadow-md overflow-hidden hover:shadow-lg transition-all duration-300">
            <div className="px-4 py-2.5 border-b border-slate-100 bg-gradient-to-r from-emerald-50 to-teal-50 flex items-center gap-2.5">
              <div className="size-8 rounded-lg bg-gradient-to-br from-emerald-500 to-teal-600 flex items-center justify-center shadow-md">
                <MapPin size={16} className="text-white" />
              </div>
              <h2 className="text-xs font-bold text-slate-900 uppercase tracking-wider">Jurisdiction</h2>
            </div>
            <div className="p-3">
              <dl className="grid grid-cols-2 gap-x-3 gap-y-2.5">
                <DetailItem label="District" value={districtName || '—'} />
                <DetailItem label="City" value={cityName || '—'} />
                <DetailItem label="Taluk" value={talukName || '—'} />
                <DetailItem label="Hobli" value={hobliName || '—'} />
                <div className="col-span-2">
                  <DetailItem
                    label="Street Address"
                    value={
                      [temple.doorNumber, effectiveAddressLine1 ?? temple.street, temple.villageTown]
                        .filter(Boolean)
                        .join(', ') || '—'
                    }
                  />
                </div>
                {effectiveLandmark && (
                  <div className="col-span-2">
                    <DetailItem label="Landmark" value={effectiveLandmark} />
                  </div>
                )}
              </dl>
            </div>
          </div>

          {/* Primary Contact */}
          <div className="bg-white rounded-xl border border-slate-200/60 shadow-md overflow-hidden hover:shadow-lg transition-all duration-300">
            <div className="px-4 py-2.5 border-b border-slate-100 bg-gradient-to-r from-purple-50 to-pink-50 flex items-center gap-2.5">
              <div className="size-8 rounded-lg bg-gradient-to-br from-purple-500 to-pink-600 flex items-center justify-center shadow-md">
                <Phone size={16} className="text-white" />
              </div>
              <h2 className="text-xs font-bold text-slate-900 uppercase tracking-wider">Primary Contact</h2>
            </div>
            <div className="p-3">
              <div className="flex items-start gap-3">
                <div className="size-10 rounded-lg bg-gradient-to-br from-purple-100 to-pink-100 border-2 border-purple-200/60 flex items-center justify-center shrink-0">
                  <UserCircle size={20} className="text-purple-600" />
                </div>
                <div className="min-w-0">
                  <p className="text-xs font-semibold text-slate-900">{effectiveContactName || '—'}</p>
                  <p className="text-[10px] text-slate-500 mt-0.5 font-regular">{effectiveContactDesignation || '—'}</p>
                  <div className="mt-2 space-y-1">
                    {effectivePhone && (
                      <a href={`tel:${effectivePhone}`} className="flex items-center gap-1.5 text-[10px] font-regular text-slate-600 hover:text-purple-600 transition-colors">
                        <Phone size={10} className="text-purple-400" />
                        {effectivePhone}
                      </a>
                    )}
                    {effectiveEmail && (
                      <a href={`mailto:${effectiveEmail}`} className="flex items-center gap-1.5 text-[10px] font-regular text-slate-600 hover:text-purple-600 transition-colors">
                        <Shield size={10} className="text-purple-400" />
                        {effectiveEmail}
                      </a>
                    )}
                    {effectiveWebsite && (
                      <a href={effectiveWebsite} target="_blank" rel="noopener noreferrer" className="flex items-center gap-1.5 text-[10px] font-regular text-slate-600 hover:text-purple-600 transition-colors">
                        <Globe size={10} className="text-purple-400" />
                        {effectiveWebsite}
                      </a>
                    )}
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* Map Card */}
          <div className="bg-white rounded-xl border border-slate-200/60 shadow-md overflow-hidden hover:shadow-lg transition-all duration-300">
            <div className="px-4 py-2.5 border-b border-slate-100 bg-gradient-to-r from-cyan-50 to-sky-50 flex items-center gap-2.5">
              <div className="size-7 rounded-lg bg-gradient-to-br from-cyan-500 to-sky-600 flex items-center justify-center shadow-md">
                <MapPin size={14} className="text-white" />
              </div>
              <h2 className="text-xs font-bold text-slate-900 uppercase tracking-wider">Physical Location</h2>
            </div>
            <div className="p-3">
              <div className="aspect-video w-full relative rounded-lg overflow-hidden border-2 border-cyan-200/60 bg-cyan-50 shadow-inner">
                {mapsEmbedUrl ? (
                  <iframe
                    title={`Map — ${temple.name}`}
                    src={mapsEmbedUrl}
                    width="100%"
                    height="100%"
                    loading="lazy"
                    className="block border-0 w-full h-full"
                  />
                ) : (
                  <div className="h-full flex flex-col items-center justify-center gap-1.5 text-center p-3">
                    <MapPin size={20} className="text-cyan-200" />
                    <p className="text-[8px] uppercase font-bold text-slate-400 tracking-widest">No Location Data</p>
                    <Button
                      variant="link"
                      className="text-[10px] text-cyan-600 h-auto p-0 underline decoration-cyan-600/30 underline-offset-2 hover:text-cyan-700"
                      onClick={() => window.open(`https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(temple.name || '')}`, '_blank')}
                    >
                      Search on Maps
                    </Button>
                  </div>
                )}
              </div>
              <Button
                variant="outline"
                size="sm"
                className="w-full mt-2.5 h-7 text-[10px] font-bold border-cyan-200 text-cyan-700 hover:bg-cyan-50 hover:border-cyan-300 rounded-lg shadow-sm transition-all"
                onClick={() => window.open(mapsEmbedUrl || `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(temple.name || '')}`, '_blank')}
              >
                Get Directions
              </Button>
            </div>
          </div>

          {/* Quick Stats Card */}
          <div className="bg-white rounded-xl border border-slate-200/60 shadow-md overflow-hidden hover:shadow-lg transition-all duration-300">
            <div className="px-4 py-2.5 border-b border-slate-100 bg-gradient-to-r from-amber-50 to-yellow-50 flex items-center gap-2.5">
              <div className="size-7 rounded-lg bg-gradient-to-br from-amber-500 to-yellow-600 flex items-center justify-center shadow-md">
                <Shield size={14} className="text-white" />
              </div>
              <h2 className="text-xs font-bold text-slate-900 uppercase tracking-wider">Quick Stats</h2>
            </div>
            <div className="p-3 space-y-2.5">
              <div>
                <p className="text-[8px] font-bold uppercase tracking-widest text-slate-400 mb-1">Management Type</p>
                <div className="flex items-center gap-2 px-2.5 py-1.5 rounded-lg bg-gradient-to-r from-amber-50 to-yellow-50 border-2 border-amber-200/60 shadow-sm">
                   <Building2 size={14} className="text-amber-600" />
                   <span className="text-xs font-semibold text-slate-700">{trust ? 'Trust Managed' : 'Individual'}</span>
                </div>
              </div>
              <div>
                <p className="text-[8px] font-bold uppercase tracking-widest text-slate-400 mb-1">Administrative Grade</p>
                <div className="flex items-center gap-2 px-2.5 py-1.5 rounded-lg bg-gradient-to-r from-emerald-50 to-teal-50 border-2 border-emerald-200/60 shadow-sm">
                   <Shield size={14} className="text-emerald-600" />
                   <span className="text-xs font-semibold text-slate-700">{temple.grade ? `Grade ${temple.grade}` : 'Unclassified'}</span>
                </div>
              </div>
            </div>
          </div>

          {/* Bank Account Details */}
          {(effectiveBankName || effectiveBankIfsc || effectiveBankAccountMasked) && (
            <div className="bg-white rounded-xl border border-slate-200/60 shadow-md overflow-hidden hover:shadow-lg transition-all duration-300">
              <div className="px-4 py-2.5 border-b border-slate-100 bg-gradient-to-r from-green-50 to-emerald-50 flex items-center gap-2.5">
                <div className="size-7 rounded-lg bg-gradient-to-br from-green-500 to-emerald-600 flex items-center justify-center shadow-md">
                  <CreditCard size={14} className="text-white" />
                </div>
                <h2 className="text-xs font-bold text-slate-900 uppercase tracking-wider">Bank Account</h2>
                {hasUnreviewedData && (actionablePendingStaging?.bankName || actionablePendingStaging?.bankIfsc || actionablePendingStaging?.bankAccountNumberMasked) && (
                  <span className="ml-auto text-[9px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full bg-amber-100 text-amber-700 border border-amber-300">
                    Pending Review
                  </span>
                )}
              </div>
              <div className="p-3">
                <dl className="grid grid-cols-1 gap-y-2.5">
                  {effectiveBankName && <DetailItem label="Bank Name" value={effectiveBankName} />}
                  {effectiveBankIfsc && <DetailItem label="IFSC Code" value={effectiveBankIfsc} />}
                  {effectiveBankAccountMasked && <DetailItem label="Account (Masked)" value={effectiveBankAccountMasked} />}
                </dl>
              </div>
            </div>
          )}
        </div>

        {/* Heritage & Profile Content — full-width, shown only when content exists */}
        {(effectiveDescription || effectiveHistoricalSignificance || effectiveAnnualFestivals || effectiveLinkedInstitutions) && (
          <div className="bg-white rounded-xl border border-slate-200/60 shadow-md overflow-hidden hover:shadow-lg transition-all duration-300">
            <div className="px-4 py-2.5 border-b border-slate-100 bg-gradient-to-r from-amber-50 to-orange-50 flex items-center gap-2.5">
              <div className="size-8 rounded-lg bg-gradient-to-br from-amber-500 to-orange-600 flex items-center justify-center shadow-md">
                <BookOpen size={16} className="text-white" />
              </div>
              <h2 className="text-xs font-bold text-slate-900 uppercase tracking-wider">Heritage & Profile Content</h2>
              {hasUnreviewedData && (
                <span className="ml-auto text-[9px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full bg-amber-100 text-amber-700 border border-amber-300">
                  Pending Review
                </span>
              )}
            </div>
            <div className="p-3 space-y-3">
              {effectiveDescription && (
                <div>
                  <p className="text-[9px] font-bold uppercase tracking-widest text-slate-400 mb-1">Description</p>
                  <p className="text-xs text-slate-700 whitespace-pre-wrap leading-relaxed">{effectiveDescription}</p>
                </div>
              )}
              {effectiveHistoricalSignificance && (
                <div className="pt-2 border-t border-slate-100">
                  <p className="text-[9px] font-bold uppercase tracking-widest text-slate-400 mb-1">Historical Significance</p>
                  <p className="text-xs text-slate-700 whitespace-pre-wrap leading-relaxed">{effectiveHistoricalSignificance}</p>
                </div>
              )}
              {effectiveAnnualFestivals && (
                <div className="pt-2 border-t border-slate-100">
                  <p className="text-[9px] font-bold uppercase tracking-widest text-slate-400 mb-1">Annual Festivals</p>
                  <p className="text-xs text-slate-700 whitespace-pre-wrap leading-relaxed">{effectiveAnnualFestivals}</p>
                </div>
              )}
              {effectiveLinkedInstitutions && (
                <div className="pt-2 border-t border-slate-100">
                  <p className="text-[9px] font-bold uppercase tracking-widest text-slate-400 mb-1">Linked Institutions</p>
                  <p className="text-xs text-slate-700 whitespace-pre-wrap leading-relaxed">{formatList(effectiveLinkedInstitutions)}</p>
                </div>
              )}
            </div>
          </div>
        )}

        {/* Temple Photo Gallery — FULL WIDTH, above governance */}
        <div className="bg-white rounded-xl border border-slate-200/60 shadow-md overflow-hidden hover:shadow-lg transition-all duration-300">
          <div className="px-4 py-2.5 border-b border-slate-100 bg-gradient-to-r from-indigo-50 to-violet-50 flex items-center gap-2.5">
            <div className="size-8 rounded-lg bg-gradient-to-br from-indigo-500 to-violet-600 flex items-center justify-center shadow-md">
              <Info size={16} className="text-white" />
            </div>
            <h2 className="text-xs font-bold text-slate-900 uppercase tracking-wider">Temple Photo Gallery</h2>
          </div>
          <div className="p-3">
            <DcTempleImageGallery templeId={temple.id} />
          </div>
        </div>

        {/* Temple Profile Governance — single card that cycles:
             Approved → Pending Review (when update submitted) → Approved.
             When pendingStaging exists, the card shows the update-under-review state
             and exposes Approve / Reject for the staging. Otherwise it shows the
             current temple verification state (Verified / Pending / Flagged) with
             the Verify / Flag temple-entity actions.
             Hidden for TEMPLE_AUTHORITY viewing other temples — governance data
             is stripped by the backend and must not leak through the UI. */}
        {(canAct || showGovernance) && (
          isRefetching ? (
            <ProfileGovernanceSectionSkeleton />
          ) : (() => {
          if (actionablePendingStaging && onApproveProfile && onRejectProfile) {
            // An update from the TA is awaiting DC review — show the governance card
            // for the profile staging, not the old verified state.
            const govStatus = actionablePendingStaging.governanceStatus
            const canApprove = !!(govStatus?.allowedActions?.includes('APPROVE')
              || govStatus?.allowedActions?.includes('RE_APPROVE'))
            const canReject = !!govStatus?.allowedActions?.includes('REJECT')
            return (
              <div className="rounded-xl overflow-hidden border border-amber-200/60 shadow-md bg-white hover:shadow-lg transition-all duration-300">
                <GovernanceActionPanel
                  entityName="Temple Profile Update"
                  isVerified={false}
                  canonicalStatus={govStatus?.status ?? actionablePendingStaging.status}
                  canAct={canAct && (canApprove || canReject)}
                  onVerify={onApproveProfile}
                  onReject={onRejectProfile}
                  statusHint={`Version ${actionablePendingStaging.version} · Submitted ${actionablePendingStaging.submittedAt ? new Date(actionablePendingStaging.submittedAt).toLocaleDateString() : 'recently'}`}
                />
              </div>
            )
          }
          // No pending update — show current temple verification / oversight state.
          const isVerified = temple.verificationStatus === 'VERIFIED'
          const flagReason = temple.verificationStatus === 'FLAGGED' ? (temple.dcFlagReason ?? 'Flagged by DC') : null
          // Expose the latest staging status so DC can see "Rejected" even after the staging
          // is no longer in a pending-review state (it was rejected and is no longer actionable).
          const latestStaging = profile.latestProfileStaging
          const latestStagingStatus = latestStaging?.status ?? null
          const latestStagingRejected = latestStagingStatus === 'REJECTED'
          // RE_APPROVED means the workflow approved a resubmission — treat it as approved
          // for display purposes, same as TrustTab and ProfileHistoryTab.
          const latestStagingApproved = latestStagingStatus === 'APPROVED' || latestStagingStatus === 'RE_APPROVED'
          const reviewedAtDisplay = latestStaging?.reviewedAt
            ? new Date(latestStaging.reviewedAt).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })
            : null
          // hasEditRejection: temple is verified (a prior profile was approved) but the
          // latest staging is REJECTED (the TA submitted an edit and it was rejected).
          // The original approved profile data remains live — mirroring TrustTab's logic.
          // When true: panel shows REJECTED badge + hint that original data is still active.
          const hasEditRejection = isVerified && latestStagingRejected
          const overrideCanonicalStatus = hasEditRejection
            ? 'REJECTED'
            : latestStagingRejected
              ? 'REJECTED'
              : (latestStagingApproved || isVerified ? 'APPROVED' : latestStagingStatus ?? undefined)
          const overrideRejectionReason = latestStagingRejected
            ? (latestStaging?.reviewComment ?? null)
            : null
          const statusHintText = hasEditRejection
            ? `Profile active · Latest update (v${latestStaging?.versionNumber}) rejected${reviewedAtDisplay ? ' on ' + reviewedAtDisplay : ''}. Original approved data is still live.`
            : undefined
          // panelIsVerified: true when the profile is in a clean approved state with no
          // pending or rejected edits. Mirrors TrustTab's panelIsVerified logic.
          const panelIsVerified = (latestStagingApproved || isVerified) && !latestStagingRejected
          const noSubmissionHint = canAct && !pendingStaging && !hasEditRejection
            ? 'The temple authority has not yet submitted their profile for review. Once they submit, you will be able to review and approve or reject it here.'
            : statusHintText
          return (
            <div className="rounded-xl overflow-hidden border border-slate-200/60 shadow-md bg-white hover:shadow-lg transition-all duration-300">
              <GovernanceActionPanel
                entityName="Temple Oversight"
                isVerified={panelIsVerified}
                flagReason={flagReason}
                canonicalStatus={overrideCanonicalStatus}
                rejectionReason={overrideRejectionReason}
                statusHint={noSubmissionHint}
                canAct={canAct && !!pendingStaging}
                onVerify={onVerifyTemple}
                onFlag={onFlagTemple}
                onReject={onFlagTemple}
              />
            </div>
          )
        })()
        )}

      </div>
    </div>
  )
}
