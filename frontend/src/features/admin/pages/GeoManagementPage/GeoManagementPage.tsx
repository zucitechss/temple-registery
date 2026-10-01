import { useState } from 'react'
import { toast } from 'sonner'
import {
  useGetStatesQuery, useCreateStateMutation,
  useGetCitiesQuery, useCreateCityMutation,
  useGetDistrictsByStateQuery, useCreateDistrictMutation,
  useGetTaluksQuery, useCreateTalukMutation,
  useGetHoblisQuery, useCreateHobliMutation
} from '@/features/geo/geoApi'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/feedback/Skeleton/Skeleton'
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
} from '@/components/ui/sheet'
import { Badge } from '@/components/ui/badge'
import { Plus, MapPin, ChevronRight, Loader2, FolderOpen, Building2, Map, Layers } from 'lucide-react'
import { cn } from '@/lib/utils'

// ─── Types ─────────────────────────────────────────────────────────────────────

type GeoLevel = 'state' | 'district' | 'taluk' | 'hobli'

interface LevelMeta {
  level: GeoLevel
  label: string
  pluralLabel: string
  icon: React.ReactNode
}

const LEVEL_META: LevelMeta[] = [
  { level: 'state',    label: 'State',    pluralLabel: 'States',    icon: <Map size={16} /> },
  { level: 'district', label: 'District', pluralLabel: 'Districts', icon: <Building2 size={16} /> },
  { level: 'taluk',    label: 'Taluk',    pluralLabel: 'Taluks',    icon: <Layers size={16} /> },
  { level: 'hobli',    label: 'Hobli',    pluralLabel: 'Hoblis',    icon: <FolderOpen size={16} /> },
]

// ─── Tree Navigator ─────────────────────────────────────────────────────────────

interface TreeSectionProps {
  meta: LevelMeta
  items: Array<{ id: number; name: string }>
  selectedId: string
  onSelect: (id: string) => void
  disabled?: boolean
  isLoading?: boolean
  onAdd: () => void
  isActive: boolean
}

function TreeSection({ meta, items, selectedId, onSelect, disabled, isLoading, onAdd, isActive }: TreeSectionProps) {
  return (
    <div className={cn('transition-opacity', disabled && 'opacity-50 pointer-events-none')}>
      {/* Section header */}
      <div className="flex items-center justify-between px-4 py-2 bg-muted/30 border-b border-border">
        <div className="flex items-center gap-2">
          <span className="text-muted-foreground">{meta.icon}</span>
          <span className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">{meta.pluralLabel}</span>
          {!isLoading && items.length > 0 && (
            <Badge variant="secondary" className="text-[10px] h-4 px-1.5">{items.length}</Badge>
          )}
        </div>
        <Button
          variant="ghost"
          size="sm"
          className="h-6 w-6 p-0 text-muted-foreground hover:text-primary"
          onClick={onAdd}
          disabled={disabled}
          aria-label={`Add ${meta.label}`}
        >
          <Plus size={13} />
        </Button>
      </div>

      {/* Items */}
      <div className="max-h-48 overflow-y-auto">
        {isLoading ? (
          <div className="space-y-1 p-2">
            {[1, 2, 3].map(i => <Skeleton key={i} className="h-8 rounded-md" />)}
          </div>
        ) : items.length === 0 ? (
          <div className="py-6 flex flex-col items-center justify-center text-center px-4">
            <span className="text-muted-foreground/50 mb-1">{meta.icon}</span>
            <p className="text-xs text-muted-foreground">
              {disabled ? `Select a parent first` : `No ${meta.pluralLabel.toLowerCase()} yet`}
            </p>
          </div>
        ) : (
          <ul>
            {items.map(item => (
              <li key={item.id}>
                <button
                  onClick={() => onSelect(item.id.toString())}
                  className={cn(
                    'w-full flex items-center justify-between px-4 py-2.5 text-sm text-left transition-colors hover:bg-muted/50',
                    selectedId === item.id.toString()
                      ? 'bg-primary/10 text-primary font-semibold ring-inset ring-2 ring-primary/20'
                      : 'text-foreground'
                  )}
                >
                  <span className="truncate">{item.name}</span>
                  {selectedId === item.id.toString() && (
                    <ChevronRight size={13} className="text-primary shrink-0 ml-1" />
                  )}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

// ─── Create Form Sheet ─────────────────────────────────────────────────────────

interface CreateSheetProps {
  open: boolean
  onClose: () => void
  level: GeoLevel | null
  isCreating: boolean
  onSubmit: (formData: Record<string, string>) => Promise<void>
  parentName?: string
}

function CreateSheet({ open, onClose, level, isCreating, onSubmit, parentName }: CreateSheetProps) {
  const [name, setName] = useState('')
  const [code, setCode] = useState('')

  const meta = LEVEL_META.find(m => m.level === level)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!name.trim()) return
    await onSubmit({ name: name.trim(), code: code.trim() })
    setName('')
    setCode('')
  }

  return (
    <Sheet open={open} onOpenChange={(v) => !v && onClose()}>
      <SheetContent side="right" className="w-[360px] sm:w-[400px]">
        <SheetHeader>
          <SheetTitle className="flex items-center gap-2">
            <Plus size={18} className="text-primary" />
            Add {meta?.label ?? 'Location'}
          </SheetTitle>
          {parentName && (
            <SheetDescription>
              Under: <span className="font-semibold text-foreground">{parentName}</span>
            </SheetDescription>
          )}
        </SheetHeader>

        <form onSubmit={handleSubmit} className="space-y-4 mt-6 px-6">
          <div className="space-y-1.5">
            <Label htmlFor="geo-name">{meta?.label ?? 'Name'} <span className="text-destructive">*</span></Label>
            <Input
              id="geo-name"
              placeholder={`Enter ${meta?.label?.toLowerCase() ?? 'name'}…`}
              value={name}
              onChange={e => setName(e.target.value)}
              autoFocus
            />
          </div>

          {(level === 'state' || level === 'district') && (
            <div className="space-y-1.5">
              <Label htmlFor="geo-code">Code <span className="text-muted-foreground text-xs">(optional)</span></Label>
              <Input
                id="geo-code"
                placeholder={level === 'state' ? 'e.g. KA' : 'e.g. BLR'}
                value={code}
                onChange={e => setCode(e.target.value)}
              />
            </div>
          )}

          <div className="flex gap-2 pt-2">
            <Button type="submit" disabled={!name.trim() || isCreating} className="flex-1 gap-2">
              {isCreating ? <Loader2 size={14} className="animate-spin" /> : <Plus size={14} />}
              Create {meta?.label}
            </Button>
            <Button type="button" variant="outline" onClick={onClose}>Cancel</Button>
          </div>
        </form>
      </SheetContent>
    </Sheet>
  )
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export function GeoManagementPage() {
  const [selectedState, setSelectedState] = useState('')
  const [selectedDistrict, setSelectedDistrict] = useState('')
  const [selectedTaluk, setSelectedTaluk] = useState('')

  const [sheetLevel, setSheetLevel] = useState<GeoLevel | null>(null)
  const [sheetOpen, setSheetOpen] = useState(false)

  // ── Queries ──
  const { data: statesRes, isFetching: loadingStates } = useGetStatesQuery()
  const { data: citiesRes } = useGetCitiesQuery(Number(selectedState), { skip: !selectedState })
  const { data: districtsRes, isFetching: loadingDistricts } = useGetDistrictsByStateQuery(Number(selectedState), { skip: !selectedState })
  const { data: taluksRes, isFetching: loadingTaluks } = useGetTaluksQuery(Number(selectedDistrict), { skip: !selectedDistrict })
  const { data: hoblisRes, isFetching: loadingHoblis } = useGetHoblisQuery(Number(selectedTaluk), { skip: !selectedTaluk })

  const states = statesRes?.data ?? []
  const cities = citiesRes?.data ?? []
  const districts = districtsRes?.data ?? []
  const taluks = taluksRes?.data ?? []
  const hoblis = hoblisRes?.data ?? []

  const selectedStateObj = states.find(s => s.id === Number(selectedState))
  const selectedDistrictObj = districts.find(d => d.id === Number(selectedDistrict))
  const selectedTalukObj = taluks.find(t => t.id === Number(selectedTaluk))

  // ── Mutations ──
  const [createState, { isLoading: creatingState }] = useCreateStateMutation()
  const [createCity] = useCreateCityMutation()
  const [createDistrict, { isLoading: creatingDistrict }] = useCreateDistrictMutation()
  const [createTaluk, { isLoading: creatingTaluk }] = useCreateTalukMutation()
  const [createHobli, { isLoading: creatingHobli }] = useCreateHobliMutation()

  const isCreating = creatingState || creatingDistrict || creatingTaluk || creatingHobli

  // ── Handlers ──
  const handleSelectState = (id: string) => {
    setSelectedState(id === selectedState ? '' : id)
    setSelectedDistrict('')
    setSelectedTaluk('')
  }
  const handleSelectDistrict = (id: string) => {
    setSelectedDistrict(id === selectedDistrict ? '' : id)
    setSelectedTaluk('')
  }
  const handleSelectTaluk = (id: string) => {
    setSelectedTaluk(id === selectedTaluk ? '' : id)
  }

  const openSheet = (level: GeoLevel) => {
    setSheetLevel(level)
    setSheetOpen(true)
  }

  const handleCreate = async (formData: Record<string, string>) => {
    const { name, code } = formData
    try {
      if (sheetLevel === 'state') {
        await createState({ name, code }).unwrap()
        toast.success('State created')
      } else if (sheetLevel === 'district') {
        let cityId: number
        if (cities.length > 0) {
          cityId = cities[0].id
        } else {
          const cityResult = await createCity({
            name: `${selectedStateObj?.name ?? 'State'} City`,
            stateId: Number(selectedState)
          }).unwrap()
          cityId = cityResult.data.id
        }
        await createDistrict({ name, code, cityId }).unwrap()
        toast.success('District created')
      } else if (sheetLevel === 'taluk') {
        await createTaluk({ name, districtId: Number(selectedDistrict) }).unwrap()
        toast.success('Taluk created')
      } else if (sheetLevel === 'hobli') {
        await createHobli({ name, talukId: Number(selectedTaluk) }).unwrap()
        toast.success('Hobli created')
      }
      setSheetOpen(false)
    } catch {
      toast.error(`Failed to create ${sheetLevel}`)
    }
  }

  const sheetParentName = sheetLevel === 'district' ? selectedStateObj?.name
    : sheetLevel === 'taluk' ? selectedDistrictObj?.name
    : sheetLevel === 'hobli' ? selectedTalukObj?.name
    : undefined

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <div className="flex items-center gap-2 mb-1">
          <MapPin size={20} className="text-primary" />
          <h1 className="text-2xl font-bold tracking-tight">Geo Hierarchy Management</h1>
        </div>
        <p className="text-sm text-muted-foreground">
          Manage geographic hierarchy. Select a level in the left panel to drill down.
        </p>
      </div>

      {/* Clickable breadcrumb trail */}
      <nav className="flex items-center gap-1 flex-wrap text-sm" aria-label="Geographic selection breadcrumb">
        <button
          onClick={() => { setSelectedState(''); setSelectedDistrict(''); setSelectedTaluk('') }}
          className={cn(
            'px-3 py-1 rounded-full font-medium text-xs transition-colors',
            !selectedState ? 'bg-muted text-muted-foreground' : 'bg-primary/10 text-primary hover:bg-primary/20 cursor-pointer'
          )}
        >
          All States
        </button>
        {selectedStateObj && (
          <>
            <ChevronRight size={14} className="text-muted-foreground" />
            <button
              onClick={() => { setSelectedDistrict(''); setSelectedTaluk('') }}
              className="px-3 py-1 rounded-full font-medium text-xs bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
            >
              {selectedStateObj.name}
            </button>
          </>
        )}
        {selectedDistrictObj && (
          <>
            <ChevronRight size={14} className="text-muted-foreground" />
            <button
              onClick={() => setSelectedTaluk('')}
              className="px-3 py-1 rounded-full font-medium text-xs bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
            >
              {selectedDistrictObj.name}
            </button>
          </>
        )}
        {selectedTalukObj && (
          <>
            <ChevronRight size={14} className="text-muted-foreground" />
            <span className="px-3 py-1 rounded-full font-medium text-xs bg-primary text-primary-foreground">
              {selectedTalukObj.name}
            </span>
          </>
        )}
      </nav>

      {/* Two-panel layout */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* Left panel — tree navigator */}
        <div className="rounded-xl border border-border bg-card shadow-sm overflow-hidden divide-y divide-border">
          <TreeSection
            meta={LEVEL_META[0]}
            items={states}
            selectedId={selectedState}
            onSelect={handleSelectState}
            isLoading={loadingStates}
            onAdd={() => openSheet('state')}
            isActive={true}
          />
          <TreeSection
            meta={LEVEL_META[1]}
            items={districts}
            selectedId={selectedDistrict}
            onSelect={handleSelectDistrict}
            disabled={!selectedState}
            isLoading={loadingDistricts}
            onAdd={() => openSheet('district')}
            isActive={!!selectedState}
          />
          <TreeSection
            meta={LEVEL_META[2]}
            items={taluks}
            selectedId={selectedTaluk}
            onSelect={handleSelectTaluk}
            disabled={!selectedDistrict}
            isLoading={loadingTaluks}
            onAdd={() => openSheet('taluk')}
            isActive={!!selectedDistrict}
          />
          <TreeSection
            meta={LEVEL_META[3]}
            items={hoblis}
            selectedId=""
            onSelect={() => {}}
            disabled={!selectedTaluk}
            isLoading={loadingHoblis}
            onAdd={() => openSheet('hobli')}
            isActive={!!selectedTaluk}
          />
        </div>

        {/* Right panel — context info */}
        <div className="rounded-xl border border-border bg-card shadow-sm p-6 flex flex-col gap-4">
          <h3 className="text-sm font-semibold text-muted-foreground uppercase tracking-wide">Summary</h3>
          <div className="grid grid-cols-2 gap-3">
            {[
              { label: 'States', value: states.length, icon: <Map size={16} />, active: true },
              { label: 'Districts', value: districts.length, icon: <Building2 size={16} />, active: !!selectedState },
              { label: 'Taluks', value: taluks.length, icon: <Layers size={16} />, active: !!selectedDistrict },
              { label: 'Hoblis', value: hoblis.length, icon: <FolderOpen size={16} />, active: !!selectedTaluk },
            ].map(stat => (
              <div
                key={stat.label}
                className={cn(
                  'rounded-lg border p-3 flex items-center gap-3 transition-colors',
                  stat.active ? 'border-primary/20 bg-primary/5' : 'border-border bg-muted/20 opacity-50'
                )}
              >
                <span className={cn('text-muted-foreground', stat.active && 'text-primary')}>{stat.icon}</span>
                <div>
                  <p className="text-xl font-bold leading-none">{stat.value}</p>
                  <p className="text-xs text-muted-foreground mt-0.5">{stat.label}</p>
                </div>
              </div>
            ))}
          </div>

          {!selectedState && (
            <div className="flex-1 flex flex-col items-center justify-center text-center py-8 text-muted-foreground">
              <MapPin size={40} className="mb-3 opacity-30" />
              <p className="text-sm font-medium">Select a state to drill down</p>
              <p className="text-xs mt-1">Use the left panel to navigate the geographic hierarchy</p>
            </div>
          )}

          {selectedState && (
            <div className="space-y-2 text-sm">
              {selectedStateObj && (
                <div className="flex items-center justify-between p-3 rounded-lg border border-primary/20 bg-primary/5">
                  <span className="font-semibold text-primary">{selectedStateObj.name}</span>
                  <Badge variant="outline" className="text-xs">{districts.length} districts</Badge>
                </div>
              )}
              {selectedDistrictObj && (
                <div className="flex items-center justify-between p-3 rounded-lg border border-border bg-muted/20">
                  <span className="font-medium">{selectedDistrictObj.name}</span>
                  <Badge variant="outline" className="text-xs">{taluks.length} taluks</Badge>
                </div>
              )}
              {selectedTalukObj && (
                <div className="flex items-center justify-between p-3 rounded-lg border border-border bg-muted/20">
                  <span className="font-medium">{selectedTalukObj.name}</span>
                  <Badge variant="outline" className="text-xs">{hoblis.length} hoblis</Badge>
                </div>
              )}
            </div>
          )}

          <div className="mt-auto pt-4 border-t border-border space-y-2">
            <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">Quick Add</p>
            <div className="grid grid-cols-2 gap-2">
              <Button variant="outline" size="sm" className="gap-1.5" onClick={() => openSheet('state')}>
                <Plus size={12} /> State
              </Button>
              <Button variant="outline" size="sm" className="gap-1.5" disabled={!selectedState} onClick={() => openSheet('district')}>
                <Plus size={12} /> District
              </Button>
              <Button variant="outline" size="sm" className="gap-1.5" disabled={!selectedDistrict} onClick={() => openSheet('taluk')}>
                <Plus size={12} /> Taluk
              </Button>
              <Button variant="outline" size="sm" className="gap-1.5" disabled={!selectedTaluk} onClick={() => openSheet('hobli')}>
                <Plus size={12} /> Hobli
              </Button>
            </div>
          </div>
        </div>
      </div>

      {/* Create Sheet */}
      <CreateSheet
        open={sheetOpen}
        onClose={() => setSheetOpen(false)}
        level={sheetLevel}
        isCreating={isCreating}
        onSubmit={handleCreate}
        parentName={sheetParentName}
      />
    </div>
  )
}


