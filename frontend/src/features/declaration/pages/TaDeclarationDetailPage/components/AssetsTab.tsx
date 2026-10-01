import { FileText, Landmark, Building2, FileCheck, MapPin, Coins, Gem, Car, Wrench, TrendingUp } from 'lucide-react'
import type { CompleteDeclarationResponse } from '../../../declarationTypes'
import { AssetGroup } from './AssetGroup'
import { AssetSection } from './AssetSection'
import { DetailedAssetCard } from './DetailedAssetCard'

interface AssetsTabProps {
  declaration: CompleteDeclarationResponse
}

export function AssetsTab({ declaration }: AssetsTabProps) {
  return (
    <div className="space-y-4">
      <AssetGroup title="Immovable Assets" icon={<Landmark size={16} />}>
        <AssetSection
          title="Agricultural Land"
          items={declaration.agriculturalLands}
          renderItem={(item) => (
            <DetailedAssetCard
              icon={<MapPin size={14} />}
              fields={[
                { label: 'Survey Number', value: item.surveyNumber || '—' },
                { label: 'Village', value: item.village || '—' },
                { label: 'Area', value: `${item.areaAcres || 0} acres`, highlight: true },
                { label: 'Owner of Record', value: item.ownerOfRecord || '—' },
                { label: 'Patta Status', value: item.pattaStatus || '—' },
              ]}
            />
          )}
        />
        <AssetSection
          title="Buildings"
          items={declaration.buildings}
          renderItem={(item) => (
            <DetailedAssetCard
              icon={<Building2 size={14} />}
              fields={[
                { label: 'Location', value: item.location || '—' },
                { label: 'Total Area', value: `${item.totalAreaSqft || 0} sq ft`, highlight: true },
                { label: 'Year Built', value: item.yearBuilt?.toString() || '—' },
                { label: 'Structure Type', value: item.structureType || '—' },
                { label: 'Valuation', value: formatCurrency(item.valuationInr || 0) },
              ]}
            />
          )}
        />
        <AssetSection
          title="Leased Properties"
          items={declaration.leasedProperties}
          renderItem={(item) => (
            <DetailedAssetCard
              icon={<FileCheck size={14} />}
              fields={[
                { label: 'Property Address', value: item.propertyAddress || '—' },
                { label: 'Lessee Name', value: item.lesseeName || '—' },
                { label: 'Lease Start Date', value: formatDate(item.leaseStartDate) },
                { label: 'Lease End Date', value: formatDate(item.leaseEndDate) },
                { label: 'Monthly Rent', value: formatCurrency(item.monthlyRent || 0), highlight: true },
                { label: 'Agreement Document', value: item.agreementDocumentId ? `Doc #${item.agreementDocumentId}` : 'No PDF' },
              ]}
            />
          )}
        />
        <AssetSection
          title="Other Land Holdings"
          items={declaration.otherLands}
          renderItem={(item) => (
            <DetailedAssetCard
              icon={<MapPin size={14} />}
              fields={[
                { label: 'Location', value: item.location || '—' },
                { label: 'Area', value: `${item.area || 0} units`, highlight: true },
                { label: 'Usage Type', value: item.usageType || '—' },
                { label: 'Revenue Dept Reference', value: item.revenueDepartmentReference || '—' },
              ]}
            />
          )}
        />
      </AssetGroup>

      <AssetGroup title="Movable Assets" icon={<Coins size={16} />}>
        <AssetSection
          title="Gold & Silver"
          items={declaration.preciousMetals}
          renderItem={(item) => (
            <DetailedAssetCard
              icon={<Coins size={14} />}
              fields={[
                { label: 'Item Description', value: item.itemDescription || '—' },
                { label: 'Metal Type', value: item.metalType || '—' },
                { label: 'Weight', value: `${item.weightGrams || 0} g`, highlight: true },
                { label: 'Purity', value: item.purity || '—' },
                { label: 'Approximate Value', value: formatCurrency(item.approximateValueInr || 0) },
              ]}
            />
          )}
        />
        <AssetSection
          title="Artifacts"
          items={declaration.artifacts}
          renderItem={(item) => (
            <DetailedAssetCard
              icon={<Gem size={14} />}
              fields={[
                { label: 'Item Description', value: item.itemDescription || '—' },
                { label: 'Material', value: item.material || '—' },
                { label: 'Age/Period', value: item.ageOrPeriod || '—' },
                { label: 'Provenance', value: item.provenance || '—' },
                { label: 'Museum Grade', value: item.museumGradeClassification || '—' },
                { label: 'Approximate Value', value: formatCurrency(item.approximateValueInr || 0), highlight: true },
              ]}
            />
          )}
        />
        <AssetSection
          title="Vehicles"
          items={declaration.vehicles}
          renderItem={(item) => (
            <DetailedAssetCard
              icon={<Car size={14} />}
              fields={[
                { label: 'Registration Number', value: item.registrationNumber || '—', highlight: true },
                { label: 'Make & Model', value: item.makeModel || '—' },
                { label: 'Year', value: item.year?.toString() || '—' },
                { label: 'Purpose', value: item.purpose || '—' },
                { label: 'Approximate Value', value: formatCurrency(item.approximateValueInr || 0) },
              ]}
            />
          )}
        />
        <AssetSection
          title="Equipment"
          items={declaration.equipment}
          renderItem={(item) => (
            <DetailedAssetCard
              icon={<Wrench size={14} />}
              fields={[
                { label: 'Item Name', value: item.itemName || '—' },
                { label: 'Serial Number', value: item.serialNumber || '—' },
                { label: 'Approximate Value', value: formatCurrency(item.approximateValueInr || 0), highlight: true },
              ]}
            />
          )}
        />
        <AssetSection
          title="Financial Assets"
          items={declaration.financialAssets}
          renderItem={(item) => (
            <DetailedAssetCard
              icon={<TrendingUp size={14} />}
              fields={[
                { label: 'Asset Subtype', value: item.assetSubtype || '—' },
                { label: 'Bank Name', value: item.bankName || '—' },
                { label: 'Amount', value: formatCurrency(item.amount || 0), highlight: true },
                { label: 'Maturity Date', value: item.maturityDate ? formatDate(item.maturityDate) : '—' },
              ]}
            />
          )}
        />
      </AssetGroup>
    </div>
  )
}

function formatDate(value?: string | null) {
  if (!value) return '—'
  return new Date(value).toLocaleDateString('en-IN')
}

function formatCurrency(value: number) {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(value)
}
