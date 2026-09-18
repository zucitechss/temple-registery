import React, { useState, useRef, useCallback, useEffect } from 'react'
import { UploadCloud, X, AlertCircle, RefreshCcw, CheckCircle2, Image as ImageIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { toast } from 'sonner'
import { useUploadTemplePhotosMutation } from '../hooks/templeApi'

interface FileUploadState {
  id: string
  file: File
  preview: string
  status: 'pending' | 'uploading' | 'completed' | 'error'
  progress: number
  errorMessage?: string
}

interface MultipleImageUploadProps {
  templeId: number
  onUploadSuccess?: (urls: string[]) => void
  onUploadError?: (error: any) => void
}

const MAX_FILE_SIZE = 5 * 1024 * 1024 // 5MB (VAL-006)
const MAX_FILES = 20

/**
 * H-4 — the API accepts at most 25MB per multipart request
 * (spring.servlet.multipart.max-request-size). At 5MB per photo, four fit with room for the
 * multipart envelope, so a selection larger than this is uploaded as several requests rather
 * than one that the server would reject outright. Sending all 20 at once could reach ~100MB,
 * and the server reads those bytes into memory.
 */
export const PHOTO_UPLOAD_BATCH_SIZE = 4
const ALLOWED_TYPES = ['image/jpeg', 'image/png']

function extractUploadErrorMessage(err: any): string {
  const validationMessage = err?.data?.errors?.[0]
  if (typeof validationMessage === 'string') return validationMessage
  return err?.data?.message || err?.message || 'Failed to upload images'
}

export const MultipleImageUpload: React.FC<MultipleImageUploadProps> = ({
  templeId,
  onUploadSuccess,
  onUploadError,
}) => {
  const [files, setFiles] = useState<FileUploadState[]>([])
  const [dragActive, setDragActive] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [uploadPhotos, { isLoading: isUploadingGlobal }] = useUploadTemplePhotosMutation()

  const handleFiles = useCallback((selectedFiles: FileList | File[]) => {
    const newFiles: FileUploadState[] = []
    const invalidFiles: string[] = []

    Array.from(selectedFiles).forEach((file) => {
      if (!ALLOWED_TYPES.includes(file.type)) {
        invalidFiles.push(`${file.name} (Invalid format)`)
        return
      }
      if (file.size > MAX_FILE_SIZE) {
        invalidFiles.push(`${file.name} (Too large > 5MB)`)
        return
      }

      newFiles.push({
        id: Math.random().toString(36).substring(2, 11),
        file,
        preview: URL.createObjectURL(file),
        status: 'pending',
        progress: 0,
      })
    })

    if (invalidFiles.length > 0) {
      toast.error(`Some files were rejected:\n${invalidFiles.join('\n')}`)
    }

    setFiles((prev) => {
      const combined = [...prev, ...newFiles]
      if (combined.length > MAX_FILES) {
        toast.warning(`Maximum ${MAX_FILES} images allowed. Only first ${MAX_FILES} will be kept.`)
        return combined.slice(0, MAX_FILES)
      }
      return combined
    })
  }, [])

  const removeFile = (id: string) => {
    setFiles((prev) => {
      const fileToRemove = prev.find((f) => f.id === id)
      if (fileToRemove?.preview) {
        URL.revokeObjectURL(fileToRemove.preview)
      }
      return prev.filter((f) => f.id !== id)
    })
  }

  const handleDrag = (e: React.DragEvent) => {
    e.preventDefault()
    e.stopPropagation()
    if (e.type === 'dragenter' || e.type === 'dragover') {
      setDragActive(true)
    } else if (e.type === 'dragleave') {
      setDragActive(false)
    }
  }

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault()
    e.stopPropagation()
    setDragActive(false)
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      handleFiles(e.dataTransfer.files)
    }
  }

  const handleUpload = async () => {
    const pendingFiles = files.filter((f) => f.status === 'pending' || f.status === 'error')
    if (pendingFiles.length === 0) return

    setFiles((prev) =>
      prev.map((f) =>
        f.status === 'pending' || f.status === 'error'
          ? { ...f, status: 'uploading', progress: 30 }
          : f
      )
    )

    try {
      const uploadedUrls: string[] = []

      for (let i = 0; i < pendingFiles.length; i += PHOTO_UPLOAD_BATCH_SIZE) {
        const batch = pendingFiles.slice(i, i + PHOTO_UPLOAD_BATCH_SIZE)
        const response = await uploadPhotos({
          id: templeId,
          files: batch.map((f) => f.file),
        }).unwrap()

        if (!response.success) {
          throw new Error(response.message || 'Upload failed')
        }
        uploadedUrls.push(...(response.data || []))
      }

      {
        setFiles((prev) =>
          prev.map((f) =>
            f.status === 'uploading' ? { ...f, status: 'completed', progress: 100 } : f
          )
        )
        toast.success('Images uploaded successfully')
        if (onUploadSuccess) onUploadSuccess(uploadedUrls)
        
        // Clear files after a delay
        setTimeout(() => {
          setFiles([])
        }, 2000)
      }
    } catch (err: any) {
      setFiles((prev) =>
        prev.map((f) =>
          f.status === 'uploading'
            ? { ...f, status: 'error', progress: 0, errorMessage: err.message || 'Failed' }
            : f
        )
      )
      toast.error(extractUploadErrorMessage(err))
      if (onUploadError) onUploadError(err)
    }
  }

  const retryUpload = (id: string) => {
    setFiles((prev) =>
      prev.map((f) => (f.id === id ? { ...f, status: 'pending', progress: 0 } : f))
    )
  }

  // Cleanup previews on unmount
  useEffect(() => {
    return () => {
      files.forEach((f) => URL.revokeObjectURL(f.preview))
    }
  }, [files])

  return (
    <div className="space-y-6">
      <div
        onDragEnter={handleDrag}
        onDragLeave={handleDrag}
        onDragOver={handleDrag}
        onDrop={handleDrop}
        className={cn(
          'relative flex flex-col items-center justify-center w-full h-48 border-2 border-dashed rounded-xl transition-all duration-300',
          dragActive
            ? 'border-primary bg-primary/5 ring-4 ring-primary/10'
            : 'border-muted-foreground/20 hover:border-primary/40 bg-muted/20 shadow-sm hover:shadow-md',
          isUploadingGlobal && 'opacity-50 cursor-not-allowed'
        )}
      >
        <div className="flex flex-col items-center justify-center pt-5 pb-6">
          <div className={cn(
            "p-3 rounded-full mb-3 transition-colors duration-300",
            dragActive ? "bg-primary/20 text-primary" : "bg-muted text-muted-foreground"
          )}>
            <UploadCloud className="w-8 h-8" />
          </div>
          <p className="mb-2 text-sm text-foreground font-medium">
            <span className="text-primary font-bold">Click to upload</span> or drag and drop
          </p>
          <p className="text-xs text-muted-foreground">
            JPEG or PNG (MAX. 5MB per image)
          </p>
          <p className="text-xs text-muted-foreground mt-1">
            The first image in the selection will be designated as the <span className="text-primary font-medium">Primary Photo</span>.
          </p>
        </div>
        <input
          ref={fileInputRef}
          type="file"
          className="hidden"
          multiple
          accept={ALLOWED_TYPES.join(',')}
          onChange={(e) => e.target.files && handleFiles(e.target.files)}
          disabled={isUploadingGlobal}
        />
        <Button
          type="button"
          variant="ghost"
          className="absolute inset-0 w-full h-full opacity-0 cursor-pointer"
          onClick={() => fileInputRef.current?.click()}
          disabled={isUploadingGlobal}
        />
      </div>

      {files.length > 0 && (
        <div className="space-y-4 animate-in fade-in slide-in-from-top-4 duration-300">
          <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-4">
            {files.map((file, index) => (
              <div
                key={file.id}
                className="group relative aspect-square rounded-xl border bg-muted overflow-hidden shadow-sm hover:shadow-lg transition-all duration-300"
              >
                <img
                  src={file.preview}
                  alt="preview"
                  className="h-full w-full object-cover transition-transform duration-500 group-hover:scale-110"
                />
                
                {/* Primary Badge for the first file */}
                {index === 0 && file.status === 'pending' && (
                  <div className="absolute top-2 left-2 bg-primary/90 text-primary-foreground text-[10px] font-bold px-2 py-0.5 rounded shadow-sm z-10 backdrop-blur-sm">
                    PRIMARY
                  </div>
                )}
                
                {/* Overlay for status */}
                <div className={cn(
                  "absolute inset-0 flex flex-col items-center justify-center bg-black/50 transition-opacity duration-300",
                  file.status === 'pending' ? 'opacity-0 group-hover:opacity-100' : 'opacity-100'
                )}>
                  {file.status === 'uploading' && (
                    <div className="w-4/5">
                      <div className="h-1.5 w-full bg-white/20 rounded-full overflow-hidden">
                        <div 
                          className="h-full bg-primary transition-all duration-300" 
                          style={{ width: `${file.progress}%` }}
                        />
                      </div>
                      <p className="text-[10px] text-white mt-1 text-center font-medium">Uploading...</p>
                    </div>
                  )}

                  {file.status === 'completed' && (
                    <div className="flex flex-col items-center animate-in zoom-in duration-500">
                      <div className="bg-green-500/20 p-2 rounded-full backdrop-blur-sm">
                        <CheckCircle2 className="w-8 h-8 text-green-400" />
                      </div>
                      <p className="text-[10px] text-white mt-2 font-bold tracking-wider">COMPLETED</p>
                    </div>
                  )}

                  {file.status === 'error' && (
                    <div className="flex flex-col items-center">
                      <div className="bg-red-500/20 p-2 rounded-full backdrop-blur-sm">
                        <AlertCircle className="w-8 h-8 text-red-400" />
                      </div>
                      <p className="text-[10px] text-white mt-2 font-bold tracking-wider">FAILED</p>
                      <button
                        onClick={() => retryUpload(file.id)}
                        className="mt-2 p-1.5 bg-white/10 rounded-full hover:bg-white/30 transition-colors"
                        title="Retry"
                      >
                        <RefreshCcw className="w-4 h-4 text-white" />
                      </button>
                    </div>
                  )}

                  {file.status === 'pending' && (
                    <button
                      onClick={() => removeFile(file.id)}
                      className="p-2 bg-red-500/90 text-white rounded-full hover:bg-red-600 transition-all shadow-lg hover:scale-110 active:scale-95"
                      title="Remove"
                    >
                      <X className="w-4 h-4" />
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>

          <div className="flex justify-end gap-3 pt-2">
            <Button
              type="button"
              variant="outline"
              className="rounded-lg border-muted-foreground/20 text-muted-foreground hover:bg-muted"
              onClick={() => {
                files.forEach((f) => URL.revokeObjectURL(f.preview))
                setFiles([])
              }}
              disabled={isUploadingGlobal}
            >
              Clear All
            </Button>
            <Button
              type="button"
              className="bg-primary hover:bg-primary/90 text-primary-foreground rounded-lg shadow-md hover:shadow-lg transition-all font-medium px-6"
              onClick={handleUpload}
              disabled={isUploadingGlobal || files.length === 0}
            >
              {isUploadingGlobal ? 'Uploading...' : `Upload ${files.length} Images`}
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}
