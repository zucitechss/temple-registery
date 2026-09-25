import { useState, useRef } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { Button } from '@/components/ui/button'
import { useAppSelector } from '@/app/store'
import { USER_ROLES } from '@/constants/roles'
import { createNoticeSchema, type CreateNoticeRequest } from '../noticeTypes'
import { useCreateNoticeMutation, useAddNoticeAttachmentMutation } from '../noticeApi'
import { toast } from 'sonner'
import { extractApiErrorMessage } from '@/lib/apiError'
import { Paperclip, X } from 'lucide-react'

interface CreateNoticeDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function CreateNoticeDialog({ open, onOpenChange }: CreateNoticeDialogProps) {
  const role = useAppSelector((s) => s.auth.currentUser?.role)
  const isSA = role === USER_ROLES.SUPER_ADMIN
  const [pendingFiles, setPendingFiles] = useState<File[]>([])
  const fileInputRef = useRef<HTMLInputElement>(null)

  const form = useForm<CreateNoticeRequest>({
    resolver: zodResolver(createNoticeSchema),
    defaultValues: {
      title: '',
      body: '',
      scope: 'DISTRICT',
      priority: 'MEDIUM',
      status: 'PUBLISHED',
      pinned: false,
      expiryDate: null,
    },
  })

  const [create, { isLoading: isCreating }] = useCreateNoticeMutation()
  const [addAttachment, { isLoading: isUploading }] = useAddNoticeAttachmentMutation()
  const isLoading = isCreating || isUploading

  const onSubmit = async (data: CreateNoticeRequest) => {
    try {
      const response = await create(data).unwrap()
      const noticeId = response.data?.id

      if (noticeId && pendingFiles.length > 0) {
        for (const file of pendingFiles) {
          try {
            await addAttachment({ noticeId, file }).unwrap()
          } catch (attachErr) {
            toast.warning(`Notice created but failed to upload "${file.name}": ${extractApiErrorMessage(attachErr, 'upload error')}`)
          }
        }
      }

      toast.success('Notice published successfully.')
      form.reset()
      setPendingFiles([])
      onOpenChange(false)
    } catch (err) {
      toast.error(extractApiErrorMessage(err, 'Failed to create notice.'))
    }
  }

  const addFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? [])
    const MAX_SIZE = 10 * 1024 * 1024 // 10 MB — matches NoticeServiceImpl
    const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'application/pdf']
    const valid = files.filter((f) => {
      if (f.size > MAX_SIZE) {
        toast.error(`"${f.name}" exceeds 10 MB limit.`)
        return false
      }
      if (!ALLOWED_TYPES.includes(f.type)) {
        toast.error(`"${f.name}" is not a supported file type (JPEG, PNG, PDF only).`)
        return false
      }
      return true
    })
    setPendingFiles((prev) => [...prev, ...valid].slice(0, 5))
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Create Notice</DialogTitle>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
            <FormField
              control={form.control}
              name="title"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Title</FormLabel>
                  <FormControl>
                    <Input placeholder="Enter notice title" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="body"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Body</FormLabel>
                  <FormControl>
                    <Textarea
                      placeholder="Enter notice content…"
                      rows={5}
                      {...field}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <div className="grid grid-cols-2 gap-3">
              {isSA && (
                <FormField
                  control={form.control}
                  name="scope"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Scope</FormLabel>
                      <Select onValueChange={field.onChange} value={field.value}>
                        <FormControl>
                          <SelectTrigger>
                            <SelectValue />
                          </SelectTrigger>
                        </FormControl>
                        <SelectContent>
                          <SelectItem value="DISTRICT">District</SelectItem>
                          <SelectItem value="GLOBAL">Global (All Districts)</SelectItem>
                        </SelectContent>
                      </Select>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              )}

              <FormField
                control={form.control}
                name="priority"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Priority</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl>
                        <SelectTrigger>
                          <SelectValue />
                        </SelectTrigger>
                      </FormControl>
                      <SelectContent>
                        <SelectItem value="HIGH">High</SelectItem>
                        <SelectItem value="MEDIUM">Medium</SelectItem>
                        <SelectItem value="LOW">Low</SelectItem>
                      </SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="status"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Publish as</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
                      <FormControl>
                        <SelectTrigger>
                          <SelectValue />
                        </SelectTrigger>
                      </FormControl>
                      <SelectContent>
                        <SelectItem value="PUBLISHED">Published (visible now)</SelectItem>
                        <SelectItem value="DRAFT">Draft (save for later)</SelectItem>
                      </SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </div>

            <FormField
              control={form.control}
              name="expiryDate"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Expiry Date (optional)</FormLabel>
                  <FormControl>
                    <Input
                      type="date"
                      {...field}
                      value={field.value ?? ''}
                      onChange={(e) => field.onChange(e.target.value || null)}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="pinned"
              render={({ field }) => (
                <FormItem className="flex items-center gap-3">
                  <FormControl>
                    <Switch checked={field.value} onCheckedChange={field.onChange} />
                  </FormControl>
                  <FormLabel className="!mt-0">Pin this notice</FormLabel>
                </FormItem>
              )}
            />

            {/* File attachments (client-side selection only — uploaded after notice creation in a separate mutation) */}
            <div className="space-y-2">
              <p className="text-sm font-medium">Attachments (optional, max 5)</p>
              <input
                ref={fileInputRef}
                type="file"
                accept=".pdf,.png,.jpg,.jpeg,.gif"
                multiple
                className="hidden"
                onChange={addFile}
              />
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => fileInputRef.current?.click()}
                disabled={pendingFiles.length >= 5}
              >
                <Paperclip size={14} className="mr-1" />
                Add file
              </Button>
              <div className="space-y-1">
                {pendingFiles.map((f, i) => (
                  <div key={i} className="flex items-center justify-between rounded border bg-muted/40 px-2 py-1 text-xs">
                    <span className="truncate max-w-[240px]">{f.name}</span>
                    <button
                      type="button"
                      className="ml-2 text-muted-foreground hover:text-destructive"
                      onClick={() => setPendingFiles((prev) => prev.filter((_, idx) => idx !== i))}
                    >
                      <X size={12} />
                    </button>
                  </div>
                ))}
              </div>
            </div>

            <div className="flex justify-end gap-2 pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
                Cancel
              </Button>
              <Button type="submit" disabled={isLoading}>
                {isCreating ? 'Saving…' : isUploading ? 'Uploading…' : 'Create Notice'}
              </Button>
            </div>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  )
}
