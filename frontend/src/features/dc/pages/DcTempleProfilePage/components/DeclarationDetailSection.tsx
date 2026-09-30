import { useState } from 'react'
import type { DeclarationDetailResponse } from '@/features/dc/dcTypes'
import {
  Trees,
  Building2,
  Map,
  Coins,
  CircleDot,
  Car,
  Landmark,
  Banknote,
  Package,
  Calendar,
  CheckCircle2,
  Clock,
  FileText,
  IndianRupee,
  ChevronDown,
  MapPin,
  Hash,
  Ruler,
  Wrench
} from 'lucide-react'
import { cn } from '@/lib/utils'

interface DeclarationDetailSectionProps {
  declaration: DeclarationDetailResponse
}

export function DeclarationDetailSection({ declaration }: DeclarationDetailSectionProps) {
  const [openAccordion, setOpenAccordion] = useState<string | null>(null)

  const toggleAccordion = (id: string) => {
    setOpenAccordion(openAccordion === id ? null : id)
  }

  // Compute total assets
  const totalAssets =
    (declaration.agriculturalLandValue ?? 0) +
    (declaration.buildingsValue ?? 0) +
    (declaration.financialAssetsValue ?? 0) +
    (declaration.otherMovableValue ?? 0)

  // Asset counts
  const agriLandCount = declaration.agriculturalLands?.length ?? 0
  const buildingsCount = declaration.buildings?.length ?? 0
  const leasedCount = declaration.leasedProperties?.length ?? 0
  const otherLandCount = declaration.otherLands?.length ?? 0
  const artifactsCount = declaration.artifacts?.length ?? 0
  const equipmentCount = declaration.equipment?.length ?? 0
  const preciousMetalsCount = declaration.preciousMetals?.length ?? 0
  const vehiclesCount = declaration.vehicles?.length ?? 0

  return (
    <div className="space-y-4 mt-4 animate-in fade-in slide-in-from-top-2 duration-300">
      {/* Summary Card */}
      <div className="rounded-lg border border-border/50 bg-gradient-to-br from-background to-muted/20 shadow-sm overflow-hidden">
        <div className="px-3 py-2 bg-gradient-to-r from-muted/50 to-muted/30 border-b border-border/50">
          <h3 className="text-xs font-semibold text-foreground flex items-center gap-1.5">
            <div className="h-5 w-5 rounded-md bg-emerald-500/20 flex items-center justify-center">
              <IndianRupee className="h-3 w-3 text-emerald-600" />
            </div>
            Declaration Summary
          </h3>
        </div>
        <div className="p-2">
          <dl className="grid grid-cols-2 md:grid-cols-4 gap-2">
            <div className="p-2 rounded-md border border-emerald-200/60 bg-gradient-to-br from-emerald-50/50 to-emerald-100/30">
              <dt className="text-[10px] text-muted-foreground flex items-center gap-1 mb-1">
                <IndianRupee className="h-2.5 w-2.5 text-emerald-600" />
                <span className="uppercase tracking-wider font-medium">Total Assets</span>
              </dt>
              <dd className="text-sm font-bold text-emerald-700">
                {formatCurrency(totalAssets)}
              </dd>
            </div>
            <div className="p-2 rounded-md border border-blue-200/60 bg-gradient-to-br from-blue-50/50 to-blue-100/30">
              <dt className="text-[10px] text-muted-foreground flex items-center gap-1 mb-1">
                <Calendar className="h-2.5 w-2.5 text-blue-600" />
                <span className="uppercase tracking-wider font-medium">Financial Year</span>
              </dt>
              <dd className="text-xs font-semibold text-blue-700">{declaration.financialYear}</dd>
            </div>
            <div className="p-2 rounded-md border border-purple-200/60 bg-gradient-to-br from-purple-50/50 to-purple-100/30">
              <dt className="text-[10px] text-muted-foreground flex items-center gap-1 mb-1">
                <CheckCircle2 className="h-2.5 w-2.5 text-purple-600" />
                <span className="uppercase tracking-wider font-medium">Submitted</span>
              </dt>
              <dd className="text-xs font-semibold text-purple-700">{declaration.submittedAt ? formatDate(declaration.submittedAt) : '—'}</dd>
            </div>
            <div className="p-2 rounded-md border border-amber-200/60 bg-gradient-to-br from-amber-50/50 to-amber-100/30">
              <dt className="text-[10px] text-muted-foreground flex items-center gap-1 mb-1">
                <Clock className="h-2.5 w-2.5 text-amber-600" />
                <span className="uppercase tracking-wider font-medium">Due Date</span>
              </dt>
              <dd className={cn(
                "text-xs font-semibold",
                declaration.overdue ? 'text-destructive' : 'text-amber-700'
              )}>
                {declaration.dueDate ? formatDate(declaration.dueDate) : '—'}
              </dd>
            </div>
          </dl>
        </div>
      </div>

      {/* Immovable Assets - Accordion */}
      <div className="rounded-lg border border-primary/20 bg-gradient-to-br from-primary/5 to-background shadow-sm overflow-hidden">
        <div className="px-4 py-3 bg-gradient-to-r from-primary/10 to-primary/5 border-b border-primary/20">
          <h3 className="text-sm font-semibold text-primary flex items-center gap-2">
            <div className="h-7 w-7 rounded-lg bg-primary/20 flex items-center justify-center">
              <Map className="h-3.5 w-3.5 text-primary" />
            </div>
            Immovable Assets
          </h3>
        </div>
        <div className="divide-y divide-primary/10">
          {/* Agricultural Land */}
          {agriLandCount > 0 && (
            <AccordionItem
              id="agri-land"
              title="Agricultural Land"
              icon={<Trees className="h-4 w-4" />}
              count={agriLandCount}
              value={formatCurrency(declaration.agriculturalLandValue)}
              isOpen={openAccordion === 'agri-land'}
              onToggle={() => toggleAccordion('agri-land')}
              colorTheme="emerald"
            >
              <div className="space-y-3">
                {declaration.agriculturalLands?.map((land, index) => (
                  <AssetCard
                    key={land.id}
                    index={index}
                    label="Agricultural Land"
                    value={land.areaAcres ? `${land.areaAcres} acres` : '—'}
                  >
                    <DetailField icon={<Hash size={12} />} label="Survey No." value={land.surveyNumber} />
                    <DetailField icon={<MapPin size={12} />} label="Village" value={land.village} />
                    <DetailField icon={<FileText size={12} />} label="Owner of Record" value={land.ownerOfRecord} />
                    <DetailField icon={<FileText size={12} />} label="Patta Status" value={land.pattaStatus} />
                  </AssetCard>
                ))}
              </div>
            </AccordionItem>
          )}

          {/* Buildings */}
          {buildingsCount > 0 && (
            <AccordionItem
              id="buildings"
              title="Buildings"
              icon={<Building2 className="h-4 w-4" />}
              count={buildingsCount}
              value={formatCurrency(declaration.buildingsValue)}
              isOpen={openAccordion === 'buildings'}
              onToggle={() => toggleAccordion('buildings')}
              colorTheme="blue"
            >
              <div className="space-y-3">
                {declaration.buildings?.map((building, index) => (
                  <AssetCard
                    key={building.id}
                    index={index}
                    label="Building"
                    value={formatCurrency(building.valuationInr)}
                  >
                    <DetailField icon={<MapPin size={12} />} label="Location" value={building.location} />
                    <DetailField icon={<Ruler size={12} />} label="Area" value={building.totalAreaSqft ? `${building.totalAreaSqft} sq ft` : null} />
                    <DetailField icon={<FileText size={12} />} label="Type" value={building.structureType} />
                    <DetailField icon={<IndianRupee size={12} />} label="Value" value={formatCurrency(building.valuationInr)} />
                  </AssetCard>
                ))}
              </div>
            </AccordionItem>
          )}

          {/* Leased Properties */}
          {leasedCount > 0 && (
            <AccordionItem
              id="leased"
              title="Leased Properties"
              icon={<FileText className="h-4 w-4" />}
              count={leasedCount}
              value={formatCurrency(declaration.leasedPropertiesValue)}
              isOpen={openAccordion === 'leased'}
              onToggle={() => toggleAccordion('leased')}
              colorTheme="purple"
            >
              <div className="space-y-3">
                {declaration.leasedProperties?.map((property, index) => (
                  <AssetCard
                    key={property.id}
                    index={index}
                    label="Leased Property"
                    value={formatCurrency(property.monthlyRent)}
                  >
                    <DetailField icon={<MapPin size={12} />} label="Address" value={property.propertyAddress} className="col-span-2" />
                    <DetailField icon={<FileText size={12} />} label="Lessee" value={property.lesseeName} />
                    <DetailField icon={<IndianRupee size={12} />} label="Monthly Rent" value={formatCurrency(property.monthlyRent)} />
                    <DetailField icon={<Calendar size={12} />} label="Lease End" value={property.leaseEndDate} />
                  </AssetCard>
                ))}
              </div>
            </AccordionItem>
          )}

          {/* Other Land */}
          {otherLandCount > 0 && (
            <AccordionItem
              id="other-land"
              title="Other Land"
              icon={<Map className="h-4 w-4" />}
              count={otherLandCount}
              value={formatCurrency(declaration.otherLandValue)}
              isOpen={openAccordion === 'other-land'}
              onToggle={() => toggleAccordion('other-land')}
              colorTheme="amber"
            >
              <div className="space-y-3">
                {declaration.otherLands?.map((land, index) => (
                  <AssetCard
                    key={land.id}
                    index={index}
                    label="Other Land"
                    value={land.area ? `${land.area} units` : '—'}
                  >
                    <DetailField icon={<MapPin size={12} />} label="Location" value={land.location} />
                    <DetailField icon={<FileText size={12} />} label="Usage Type" value={land.usageType} />
                    <DetailField icon={<FileText size={12} />} label="Revenue Dept Reference" value={land.revenueDepartmentReference} />
                  </AssetCard>
                ))}
              </div>
            </AccordionItem>
          )}

          {/* Empty state */}
          {agriLandCount === 0 && buildingsCount === 0 && leasedCount === 0 && otherLandCount === 0 && (
            <div className="p-6 text-center text-sm text-primary/60">
              No immovable assets declared
            </div>
          )}
        </div>
      </div>

      {/* Movable Assets - Accordion */}
      <div className="rounded-lg border border-primary/20 bg-gradient-to-br from-primary/5 to-background shadow-sm overflow-hidden">
        <div className="px-4 py-3 bg-gradient-to-r from-primary/10 to-primary/5 border-b border-primary/20">
          <h3 className="text-sm font-semibold text-primary flex items-center gap-2">
            <div className="h-7 w-7 rounded-lg bg-primary/20 flex items-center justify-center">
              <Package className="h-3.5 w-3.5 text-primary" />
            </div>
            Movable Assets
          </h3>
        </div>
        <div className="divide-y divide-primary/10">
          {/* Precious Metals */}
          {preciousMetalsCount > 0 && (
            <AccordionItem
              id="precious-metals"
              title="Precious Metals"
              icon={<Coins className="h-4 w-4" />}
              count={preciousMetalsCount}
              value=""
              isOpen={openAccordion === 'precious-metals'}
              onToggle={() => toggleAccordion('precious-metals')}
              colorTheme="amber"
            >
              <div className="space-y-3">
                {declaration.preciousMetals?.map((metal, index) => (
                  <AssetCard
                    key={metal.id}
                    index={index}
                    label="Precious Metal"
                    value={formatCurrency(metal.estimatedValueInr ?? metal.approximateValueInr)}
                  >
                    <DetailField icon={<FileText size={12} />} label="Description" value={metal.itemDescription} className="col-span-2" />
                    <DetailField icon={<Coins size={12} />} label="Metal Type" value={metal.metalType} />
                    <DetailField icon={<Ruler size={12} />} label="Weight" value={metal.weightGrams ? `${metal.weightGrams} grams` : null} />
                    <DetailField icon={<FileText size={12} />} label="Purity" value={metal.purity} />
                  </AssetCard>
                ))}
              </div>
            </AccordionItem>
          )}

          {/* Artifacts */}
          {artifactsCount > 0 && (
            <AccordionItem
              id="artifacts"
              title="Artifacts & Idols"
              icon={<Landmark className="h-4 w-4" />}
              count={artifactsCount}
              value=""
              isOpen={openAccordion === 'artifacts'}
              onToggle={() => toggleAccordion('artifacts')}
              colorTheme="rose"
            >
              <div className="space-y-3">
                {declaration.artifacts?.map((artifact, index) => (
                  <AssetCard
                    key={artifact.id}
                    index={index}
                    label="Artifact"
                    value={formatCurrency(artifact.estimatedValueInr ?? artifact.approximateValueInr)}
                  >
                    <DetailField icon={<FileText size={12} />} label="Description" value={artifact.itemDescription} className="col-span-2" />
                    <DetailField icon={<FileText size={12} />} label="Material" value={artifact.material} />
                    <DetailField icon={<Calendar size={12} />} label="Age/Period" value={artifact.ageOrPeriod} />
                    <DetailField icon={<FileText size={12} />} label="Provenance" value={artifact.provenance} />
                    <DetailField icon={<FileText size={12} />} label="Museum Grade" value={artifact.museumGradeClassification} />
                    <DetailField icon={<IndianRupee size={12} />} label="Est. Value" value={formatCurrency(artifact.estimatedValueInr ?? artifact.approximateValueInr)} />
                  </AssetCard>
                ))}
              </div>
            </AccordionItem>
          )}

          {/* Vehicles */}
          {vehiclesCount > 0 && (
            <AccordionItem
              id="vehicles"
              title="Vehicles"
              icon={<Car className="h-4 w-4" />}
              count={vehiclesCount}
              value=""
              isOpen={openAccordion === 'vehicles'}
              onToggle={() => toggleAccordion('vehicles')}
              colorTheme="cyan"
            >
              <div className="space-y-3">
                {declaration.vehicles?.map((vehicle, index) => (
                  <AssetCard
                    key={vehicle.id}
                    index={index}
                    label="Vehicle"
                    value={formatCurrency(vehicle.estimatedValueInr)}
                  >
                    <DetailField icon={<Hash size={12} />} label="Registration" value={vehicle.registrationNumber} />
                    <DetailField icon={<FileText size={12} />} label="Make & Model" value={vehicle.makeModel} />
                    <DetailField icon={<Calendar size={12} />} label="Year" value={vehicle.year?.toString()} />
                    <DetailField icon={<FileText size={12} />} label="Purpose" value={vehicle.purpose} />
                    <DetailField icon={<IndianRupee size={12} />} label="Est. Value" value={formatCurrency(vehicle.estimatedValueInr)} />
                  </AssetCard>
                ))}
              </div>
            </AccordionItem>
          )}

          {/* Equipment */}
          {equipmentCount > 0 && (
            <AccordionItem
              id="equipment"
              title="Equipment"
              icon={<Wrench className="h-4 w-4" />}
              count={equipmentCount}
              value=""
              isOpen={openAccordion === 'equipment'}
              onToggle={() => toggleAccordion('equipment')}
              colorTheme="indigo"
            >
              <div className="space-y-3">
                {declaration.equipment?.map((item, index) => (
                  <AssetCard
                    key={item.id}
                    index={index}
                    label="Equipment"
                    value={formatCurrency(item.estimatedValueInr)}
                  >
                    <DetailField icon={<FileText size={12} />} label="Name" value={item.itemName} />
                    <DetailField icon={<Hash size={12} />} label="Serial No." value={item.serialNumber} />
                    <DetailField icon={<IndianRupee size={12} />} label="Est. Value" value={formatCurrency(item.estimatedValueInr)} />
                  </AssetCard>
                ))}
              </div>
            </AccordionItem>
          )}

          {/* Empty state */}
          {preciousMetalsCount === 0 && artifactsCount === 0 && vehiclesCount === 0 && equipmentCount === 0 && (
            <div className="p-6 text-center text-sm text-primary/60">
              No movable assets declared
            </div>
          )}
        </div>
      </div>

      {/* Financial & Other */}
      <div className="rounded-lg border border-border/50 bg-gradient-to-br from-background to-muted/20 shadow-sm overflow-hidden">
        <div className="px-3 py-2 bg-gradient-to-r from-muted/50 to-muted/30 border-b border-border/50">
          <h3 className="text-xs font-semibold text-foreground flex items-center gap-1.5">
            <div className="h-5 w-5 rounded-md bg-cyan-500/20 flex items-center justify-center">
              <Banknote className="h-3 w-3 text-cyan-600" />
            </div>
            Financial Assets
          </h3>
        </div>
        <div className="p-2">
          <dl className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            <div className="p-2 rounded-md border border-cyan-200/60 bg-gradient-to-br from-cyan-50/50 to-cyan-100/30">
              <dt className="text-[10px] text-muted-foreground flex items-center gap-1 mb-1">
                <Banknote className="h-2.5 w-2.5 text-cyan-600" />
                <span className="uppercase tracking-wider font-medium">Financial Assets</span>
              </dt>
              <dd className="text-sm font-bold text-cyan-700">{formatCurrency(declaration.financialAssetsValue)}</dd>
            </div>
            <div className="p-2 rounded-md border border-indigo-200/60 bg-gradient-to-br from-indigo-50/50 to-indigo-100/30">
              <dt className="text-[10px] text-muted-foreground flex items-center gap-1 mb-1">
                <Package className="h-2.5 w-2.5 text-indigo-600" />
                <span className="uppercase tracking-wider font-medium">Other Movable</span>
              </dt>
              <dd className="text-sm font-bold text-indigo-700">{formatCurrency(declaration.otherMovableValue)}</dd>
            </div>
          </dl>
        </div>
      </div>

      {/* Metadata */}
      {declaration.acknowledgementNumber && (
        <div className="rounded-lg border border-border/50 bg-gradient-to-br from-background to-muted/20 shadow-sm overflow-hidden">
          <div className="px-3 py-2 bg-gradient-to-r from-muted/50 to-muted/30 border-b border-border/50">
            <h3 className="text-xs font-semibold text-foreground flex items-center gap-1.5">
              <div className="h-5 w-5 rounded-md bg-rose-500/20 flex items-center justify-center">
                <FileText className="h-3 w-3 text-rose-600" />
              </div>
              Metadata
            </h3>
          </div>
          <div className="p-2">
            <dl className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              <div className="p-2 rounded-md border border-rose-200/60 bg-gradient-to-br from-rose-50/50 to-rose-100/30">
                <dt className="text-[10px] text-muted-foreground flex items-center gap-1 mb-1">
                  <FileText className="h-2.5 w-2.5 text-rose-600" />
                  <span className="uppercase tracking-wider font-medium">Acknowledgement</span>
                </dt>
                <dd className="text-xs font-bold text-rose-700 font-mono">{declaration.acknowledgementNumber}</dd>
              </div>
              <div className="p-2 rounded-md border border-teal-200/60 bg-gradient-to-br from-teal-50/50 to-teal-100/30">
                <dt className="text-[10px] text-muted-foreground flex items-center gap-1 mb-1">
                  <CheckCircle2 className="h-2.5 w-2.5 text-teal-600" />
                  <span className="uppercase tracking-wider font-medium">Reviewed At</span>
                </dt>
                <dd className="text-xs font-bold text-teal-700">{declaration.reviewedAt ? formatDate(declaration.reviewedAt) : '—'}</dd>
              </div>
            </dl>
          </div>
        </div>
      )}
    </div>
  )
}

// Helper functions
function formatCurrency(v: number | null | undefined): string {
  if (v == null) return 'Not Declared'
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0
  }).format(v)
}

function fmt(v: number | null | undefined, unit = ''): string {
  if (v == null) return 'Not Declared'
  return `${v.toLocaleString('en-IN')}${unit ? ' ' + unit : ''}`
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString('en-IN', {
    day: 'numeric',
    month: 'short',
    year: 'numeric'
  })
}

// Accordion Item Component
interface AccordionItemProps {
  id: string
  title: string
  icon: React.ReactNode
  count: number
  value: string
  isOpen: boolean
  onToggle: () => void
  children: React.ReactNode
  colorTheme?: 'emerald' | 'blue' | 'purple' | 'amber' | 'rose' | 'cyan' | 'indigo' | 'orange'
}

function AccordionItem({ id, title, icon, count, value, isOpen, onToggle, children, colorTheme = 'blue' }: AccordionItemProps) {
  const colorClasses = {
    emerald: {
      iconBg: isOpen ? 'bg-emerald-500/20 text-emerald-600' : 'bg-muted/50 text-muted-foreground group-hover:bg-emerald-500/10 group-hover:text-emerald-600',
      title: isOpen ? 'text-emerald-600' : 'text-foreground group-hover:text-emerald-600',
      value: isOpen ? 'text-emerald-600' : 'text-foreground',
      chevronBg: isOpen ? 'bg-emerald-500/10 text-emerald-600' : 'bg-muted/50 text-muted-foreground group-hover:bg-emerald-500/10 group-hover:text-emerald-600',
      rowBg: isOpen && 'bg-emerald-50/30',
    },
    blue: {
      iconBg: isOpen ? 'bg-blue-500/20 text-blue-600' : 'bg-muted/50 text-muted-foreground group-hover:bg-blue-500/10 group-hover:text-blue-600',
      title: isOpen ? 'text-blue-600' : 'text-foreground group-hover:text-blue-600',
      value: isOpen ? 'text-blue-600' : 'text-foreground',
      chevronBg: isOpen ? 'bg-blue-500/10 text-blue-600' : 'bg-muted/50 text-muted-foreground group-hover:bg-blue-500/10 group-hover:text-blue-600',
      rowBg: isOpen && 'bg-blue-50/30',
    },
    purple: {
      iconBg: isOpen ? 'bg-purple-500/20 text-purple-600' : 'bg-muted/50 text-muted-foreground group-hover:bg-purple-500/10 group-hover:text-purple-600',
      title: isOpen ? 'text-purple-600' : 'text-foreground group-hover:text-purple-600',
      value: isOpen ? 'text-purple-600' : 'text-foreground',
      chevronBg: isOpen ? 'bg-purple-500/10 text-purple-600' : 'bg-muted/50 text-muted-foreground group-hover:bg-purple-500/10 group-hover:text-purple-600',
      rowBg: isOpen && 'bg-purple-50/30',
    },
    amber: {
      iconBg: isOpen ? 'bg-amber-500/20 text-amber-600' : 'bg-muted/50 text-muted-foreground group-hover:bg-amber-500/10 group-hover:text-amber-600',
      title: isOpen ? 'text-amber-600' : 'text-foreground group-hover:text-amber-600',
      value: isOpen ? 'text-amber-600' : 'text-foreground',
      chevronBg: isOpen ? 'bg-amber-500/10 text-amber-600' : 'bg-muted/50 text-muted-foreground group-hover:bg-amber-500/10 group-hover:text-amber-600',
      rowBg: isOpen && 'bg-amber-50/30',
    },
    rose: {
      iconBg: isOpen ? 'bg-rose-500/20 text-rose-600' : 'bg-muted/50 text-muted-foreground group-hover:bg-rose-500/10 group-hover:text-rose-600',
      title: isOpen ? 'text-rose-600' : 'text-foreground group-hover:text-rose-600',
      value: isOpen ? 'text-rose-600' : 'text-foreground',
      chevronBg: isOpen ? 'bg-rose-500/10 text-rose-600' : 'bg-muted/50 text-muted-foreground group-hover:bg-rose-500/10 group-hover:text-rose-600',
      rowBg: isOpen && 'bg-rose-50/30',
    },
    cyan: {
      iconBg: isOpen ? 'bg-cyan-500/20 text-cyan-600' : 'bg-muted/50 text-muted-foreground group-hover:bg-cyan-500/10 group-hover:text-cyan-600',
      title: isOpen ? 'text-cyan-600' : 'text-foreground group-hover:text-cyan-600',
      value: isOpen ? 'text-cyan-600' : 'text-foreground',
      chevronBg: isOpen ? 'bg-cyan-500/10 text-cyan-600' : 'bg-muted/50 text-muted-foreground group-hover:bg-cyan-500/10 group-hover:text-cyan-600',
      rowBg: isOpen && 'bg-cyan-50/30',
    },
    indigo: {
      iconBg: isOpen ? 'bg-indigo-500/20 text-indigo-600' : 'bg-muted/50 text-muted-foreground group-hover:bg-indigo-500/10 group-hover:text-indigo-600',
      title: isOpen ? 'text-indigo-600' : 'text-foreground group-hover:text-indigo-600',
      value: isOpen ? 'text-indigo-600' : 'text-foreground',
      chevronBg: isOpen ? 'bg-indigo-500/10 text-indigo-600' : 'bg-muted/50 text-muted-foreground group-hover:bg-indigo-500/10 group-hover:text-indigo-600',
      rowBg: isOpen && 'bg-indigo-50/30',
    },
    orange: {
      iconBg: isOpen ? 'bg-orange-500/20 text-orange-600' : 'bg-muted/50 text-muted-foreground group-hover:bg-orange-500/10 group-hover:text-orange-600',
      title: isOpen ? 'text-orange-600' : 'text-foreground group-hover:text-orange-600',
      value: isOpen ? 'text-orange-600' : 'text-foreground',
      chevronBg: isOpen ? 'bg-orange-500/10 text-orange-600' : 'bg-muted/50 text-muted-foreground group-hover:bg-orange-500/10 group-hover:text-orange-600',
      rowBg: isOpen && 'bg-orange-50/30',
    },
  }

  const colors = colorClasses[colorTheme]

  return (
    <div className={cn(
      'transition-all duration-200',
      colors.rowBg
    )}>
      <button
        onClick={onToggle}
        className={cn(
          'w-full px-4 py-3 flex items-center justify-between transition-all duration-200',
          'hover:bg-muted/40 group'
        )}
      >
        <div className="flex items-center gap-3">
          <div className={cn(
            'flex h-8 w-8 shrink-0 items-center justify-center rounded-lg transition-all duration-200',
            colors.iconBg
          )}>
            {icon}
          </div>
          <div className="text-left">
            <p className={cn(
              'text-sm font-semibold transition-colors',
              colors.title
            )}>
              {title}
            </p>
            <p className="text-xs text-muted-foreground">
              {count} {count === 1 ? 'item' : 'items'}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          {value && (
            <span className={cn(
              'text-sm font-bold transition-colors',
              colors.value
            )}>
              {value}
            </span>
          )}
          <div className={cn(
            'flex h-7 w-7 items-center justify-center rounded-lg transition-all duration-200',
            colors.chevronBg
          )}>
            <ChevronDown
              className={cn(
                'h-4 w-4 transition-transform duration-200',
                isOpen && 'rotate-180'
              )}
            />
          </div>
        </div>
      </button>
      {isOpen && (
        <div className="px-4 pb-4 pt-1 animate-in slide-in-from-top-2 duration-200">
          {children}
        </div>
      )}
    </div>
  )
}

// Detail Field Component
interface DetailFieldProps {
  icon: React.ReactNode
  label: string
  value: string | number | null | undefined
  className?: string
}

function DetailField({ icon, label, value, className }: DetailFieldProps) {
  return (
    <div className={className}>
      <dt className="text-xs text-muted-foreground flex items-center gap-1.5 mb-1.5">
        <div className="text-primary/60">{icon}</div>
        <span className="uppercase tracking-wider font-medium">{label}</span>
      </dt>
      <dd className="text-sm font-semibold text-foreground">{value ?? '—'}</dd>
    </div>
  )
}

// Asset Card Component
interface AssetCardProps {
  index: number
  label: string
  value: string
  children: React.ReactNode
}

function AssetCard({ index, label, value, children }: AssetCardProps) {
  return (
    <div className="p-4 rounded-xl border border-border/60 bg-gradient-to-br from-background/80 to-muted/30 shadow-sm hover:shadow-md transition-all duration-200 hover:border-primary/30">
      <div className="flex items-start justify-between mb-4 pb-3 border-b border-border/50">
        <div className="flex items-center gap-2">
          <div className="h-6 w-6 rounded-md bg-primary/10 flex items-center justify-center">
            <span className="text-xs font-bold text-primary">#{index + 1}</span>
          </div>
          <span className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">{label}</span>
        </div>
        <span className="text-base font-bold text-primary">{value}</span>
      </div>
      <div className="grid grid-cols-2 gap-4 text-sm">
        {children}
      </div>
    </div>
  )
}
