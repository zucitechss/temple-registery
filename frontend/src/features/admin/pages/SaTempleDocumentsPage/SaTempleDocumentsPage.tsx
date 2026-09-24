import { useRef, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { extractApiErrorMessage } from '@/lib/apiError'
import { ArrowLeft, Info, UploadCloud, Trash2, FileText } from 'lucide-react'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/feedback/Skeleton/Skeleton'
import { Input } from '@/components/ui/input'
import { ROUTE_PATHS } from '@/constants/routePaths'
import {
  useListDocumentsQuery,
  useUploadDocumentMutation,
  useSoftDeleteDocumentMutation,
} from '@/features/document/documentApi'

const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'application/pdf']
const MAX_SIZE_BYTES = 5 * 1024 * 1024 // 5 MB

function formatFileSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

export function SaTempleDocumentsPage() {
  const { templeId: rawId } = useParams<{ templeId: string }>()
  const navigate = useNavigate()
  const templeId = Number(rawId)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [labelInput, setLabelInput] = useState('')
  const [page, setPage] = useState(0)

  const { data, isLoading } = useListDocumentsQuery(
    { ownerType: 'TEMPLE', ownerId: templeId, page, size: 10 },
    { skip: !templeId },
  )
  const documents = data?.data?.content ?? []
  const totalPages = data?.data?.totalPages ?? 1

  const [uploadDocument, { isLoading: uploading }] = useUploadDocumentMutation()
  const [deleteDocument] = useSoftDeleteDocumentMutation()

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return

    if (!ALLOWED_TYPES.includes(file.type)) {
      toast.error('Only JPEG, PNG, and PDF files are allowed.')
      e.target.value = ''
      return
    }
    if (file.size > MAX_SIZE_BYTES) {
      toast.error('File size must be under 5 MB.')
      e.target.value = ''
      return
    }

    const formData = new FormData()
    formData.append('file', file)
    formData.append('ownerType', 'TEMPLE')
    formData.append('ownerId', String(templeId))
    if (labelInput.trim()) formData.append('documentLabel', labelInput.trim())

    try {
      await uploadDocument(formData).unwrap()
      toast.success('Document uploaded.')
      setLabelInput('')
      e.target.value = ''
    } catch (err) {
      toast.error(extractApiErrorMessage(err, 'Upload failed.'))
    }
  }

  const onDelete = async (id: number) => {
    if (!confirm('Delete this document?')) return
    try {
      await deleteDocument(id).unwrap()
      toast.success('Document deleted.')
    } catch (err) {
      toast.error(extractApiErrorMessage(err, 'Failed to delete document.'))
    }
  }

  return (
    <div className="space-y-6 max-w-4xl mx-auto pb-8">
      <div className="flex items-center gap-3">
        <Button
          variant="ghost"
          size="sm"
          onClick={() => navigate(ROUTE_PATHS.DC_TEMPLE_DETAIL.replace(':templeId', String(templeId)) + '?tab=documents')}
        >
          <ArrowLeft className="h-4 w-4 mr-1" />
          Back to Temple
        </Button>
      </div>

      <h1 className="text-2xl font-bold tracking-tight">Temple Documents</h1>

      <Alert className="border-blue-200 bg-blue-50 text-blue-800">
        <Info className="h-4 w-4" />
        <AlertDescription>
          You are managing documents as <strong>Super Administrator</strong>. Allowed: JPEG, PNG, PDF — max 5 MB.
        </AlertDescription>
      </Alert>

      {/* Upload section */}
      <div className="rounded-xl border border-border bg-card p-5 shadow-sm space-y-3">
        <h2 className="text-sm font-semibold uppercase text-muted-foreground tracking-wide">Upload New Document</h2>
        <div className="flex flex-col sm:flex-row gap-3 items-start sm:items-end">
          <div className="flex-1 space-y-1">
            <label className="text-sm font-medium text-foreground">Document Label (optional)</label>
            <Input
              value={labelInput}
              onChange={e => setLabelInput(e.target.value)}
              placeholder="e.g. Registration Certificate"
              maxLength={100}
            />
          </div>
          <div className="flex-1 space-y-1">
            <label className="text-sm font-medium text-foreground">File</label>
            <Input
              ref={fileInputRef}
              type="file"
              accept=".jpg,.jpeg,.png,.pdf"
              onChange={handleFileChange}
              disabled={uploading}
            />
          </div>
          <Button
            type="button"
            size="sm"
            disabled={uploading}
            onClick={() => fileInputRef.current?.click()}
          >
            <UploadCloud className="h-4 w-4 mr-2" />
            {uploading ? 'Uploading…' : 'Upload'}
          </Button>
        </div>
      </div>

      {isLoading ? (
        <div className="space-y-2">
          {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-12 w-full" />)}
        </div>
      ) : documents.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">No documents uploaded yet.</div>
      ) : (
        <>
          <div className="rounded-xl border border-border overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-muted/40 border-b border-border">
                <tr>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">File</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">Label</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">Size</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">Uploaded</th>
                  <th className="px-4 py-3 text-right font-medium text-muted-foreground">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {documents.map((doc) => (
                  <tr key={doc.id} className="hover:bg-muted/30 transition-colors">
                    <td className="px-4 py-3 font-medium flex items-center gap-2">
                      <FileText className="h-4 w-4 text-muted-foreground shrink-0" />
                      {doc.originalFilename}
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">{doc.documentLabel ?? '—'}</td>
                    <td className="px-4 py-3">{formatFileSize(doc.fileSizeBytes)}</td>
                    <td className="px-4 py-3">{new Date(doc.createdAt).toLocaleDateString('en-IN')}</td>
                    <td className="px-4 py-3 text-right">
                      <Button variant="ghost" size="icon" aria-label="Delete document" onClick={() => onDelete(doc.id)}>
                        <Trash2 className="h-4 w-4 text-destructive" />
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {totalPages > 1 && (
            <div className="flex justify-center gap-2">
              <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</Button>
              <span className="text-sm text-muted-foreground self-center">Page {page + 1} of {totalPages}</span>
              <Button variant="outline" size="sm" disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)}>Next</Button>
            </div>
          )}
        </>
      )}
    </div>
  )
}
