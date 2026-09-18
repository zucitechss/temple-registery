import { describe, it, expect, vi, beforeEach } from 'vitest'
import { screen, fireEvent, waitFor } from '@testing-library/react'
import { renderWithProviders } from '@/test/utils/renderWithProviders'
import { MultipleImageUpload, PHOTO_UPLOAD_BATCH_SIZE } from '../MultipleImageUpload'
import { useUploadTemplePhotosMutation } from '@/features/temple-profile/hooks/templeApi'

vi.mock('@/features/temple-profile/hooks/templeApi', () => ({
  templeApi: {
    reducerPath: 'templeApi',
    reducer: (s = {}) => s,
    middleware: () => (next: (a: unknown) => unknown) => (a: unknown) => next(a),
  },
  useUploadTemplePhotosMutation: vi.fn(),
}))

const mockUpload = vi.fn()

describe('MultipleImageUpload', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(useUploadTemplePhotosMutation as any).mockReturnValue([
      mockUpload,
      { isLoading: false },
    ])
    
    // Mock URL.createObjectURL and URL.revokeObjectURL
    global.URL.createObjectURL = vi.fn(() => 'test-preview-url')
    global.URL.revokeObjectURL = vi.fn()
  })

  it('should_renderUploadZone', () => {
    renderWithProviders(<MultipleImageUpload templeId={1} />)
    expect(screen.getByText(/Click to upload/i)).toBeInTheDocument()
    expect(screen.getByText(/JPEG or PNG/i)).toBeInTheDocument()
  })

  it('should_handleFileSelection', async () => {
    renderWithProviders(<MultipleImageUpload templeId={1} />)
    
    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    const file = new File(['test'], 'test.png', { type: 'image/png' })
    
    fireEvent.change(input, { target: { files: [file] } })

    await waitFor(() => {
      expect(screen.getByAltText('preview')).toBeInTheDocument()
      expect(screen.getByText('Upload 1 Images')).toBeInTheDocument()
    })
  })

  it('should_showValidationError_forInvalidFiles', async () => {
    renderWithProviders(<MultipleImageUpload templeId={1} />)
    
    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    const file = new File(['test'], 'test.txt', { type: 'text/plain' })
    
    fireEvent.change(input, { target: { files: [file] } })

    // toast.error is called, but we don't mock toast here, we just check no preview
    expect(screen.queryByAltText('preview')).not.toBeInTheDocument()
  })

  it('should_requireMinimumFiles_forUpload', async () => {
    renderWithProviders(<MultipleImageUpload templeId={1} />)
    
    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    const files = Array.from({ length: 3 }).map((_, i) => new File(['test'], `test${i}.png`, { type: 'image/png' }))
    
    fireEvent.change(input, { target: { files } })
    
    // Upload button should show the count
    expect(screen.getByText('Upload 3 Images')).toBeInTheDocument()
  })

  it('should_callUploadMutation_whenValidFilesArePresent', async () => {
    mockUpload.mockReturnValue({ unwrap: () => Promise.resolve({ success: true, data: ['url1', 'url2', 'url3', 'url4', 'url5'] }) })
    
    renderWithProviders(<MultipleImageUpload templeId={1} />)
    
    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    const files = Array.from({ length: 5 }).map((_, i) => new File(['test'], `test${i}.png`, { type: 'image/png' }))
    
    fireEvent.change(input, { target: { files } })
    
    const uploadButton = screen.getByText('Upload 5 Images')
    fireEvent.click(uploadButton)

    await waitFor(() => {
      expect(mockUpload).toHaveBeenCalledWith({
        id: 1,
        files: expect.any(Array),
      })
    })
  })

  /**
   * H-4 — the server caps a multipart request at 25MB (spring.servlet.multipart
   * .max-request-size). This component allows 20 photos of up to 5MB each, so sending them
   * as one request could reach ~100MB: the whole selection would be rejected, and the bytes
   * are read into memory server-side. The selection is therefore split into batches that fit
   * inside the request budget.
   */
  describe('request-size batching', () => {
    const selectPngFiles = (count: number) => {
      const input = document.querySelector('input[type="file"]') as HTMLInputElement
      const files = Array.from({ length: count }).map(
        (_, i) => new File(['test'], `photo${i}.png`, { type: 'image/png' }),
      )
      fireEvent.change(input, { target: { files } })
      return files
    }

    beforeEach(() => {
      mockUpload.mockReturnValue({
        unwrap: () => Promise.resolve({ success: true, data: [] }),
      })
    })

    it('should_sendOneRequest_when_theSelectionFitsInTheRequestBudget', async () => {
      renderWithProviders(<MultipleImageUpload templeId={1} />)
      selectPngFiles(PHOTO_UPLOAD_BATCH_SIZE)

      fireEvent.click(screen.getByText(`Upload ${PHOTO_UPLOAD_BATCH_SIZE} Images`))

      await waitFor(() => expect(mockUpload).toHaveBeenCalledTimes(1))
    })

    it('should_splitIntoBatches_when_moreFilesAreSelectedThanFitInOneRequest', async () => {
      const total = PHOTO_UPLOAD_BATCH_SIZE * 2 + 1
      renderWithProviders(<MultipleImageUpload templeId={1} />)
      selectPngFiles(total)

      fireEvent.click(screen.getByText(`Upload ${total} Images`))

      await waitFor(() => expect(mockUpload).toHaveBeenCalledTimes(3))

      mockUpload.mock.calls.forEach(([arg]) => {
        expect(arg.files.length).toBeLessThanOrEqual(PHOTO_UPLOAD_BATCH_SIZE)
      })
    })

    it('should_sendEverySelectedFileExactlyOnce_when_batching', async () => {
      const total = PHOTO_UPLOAD_BATCH_SIZE * 2 + 1
      renderWithProviders(<MultipleImageUpload templeId={1} />)
      const selected = selectPngFiles(total)

      fireEvent.click(screen.getByText(`Upload ${total} Images`))

      await waitFor(() => expect(mockUpload).toHaveBeenCalledTimes(3))

      const sent = mockUpload.mock.calls.flatMap(([arg]) => arg.files as File[])
      expect(sent).toHaveLength(total)
      expect(sent.map((f) => f.name).sort()).toEqual(selected.map((f) => f.name).sort())
    })
  })
})
