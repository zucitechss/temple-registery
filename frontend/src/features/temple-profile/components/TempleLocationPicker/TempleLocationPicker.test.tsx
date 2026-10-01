import { describe, it, expect, vi, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '@/test/utils/renderWithProviders'
import { TempleLocationPicker } from './TempleLocationPicker'

// Mock @react-google-maps/api
vi.mock('@react-google-maps/api', () => ({
  useJsApiLoader: vi.fn(),
  GoogleMap: ({ children, onClick }: any) => (
    <div data-testid="google-map" onClick={onClick}>{children}</div>
  ),
  Marker: ({ position, draggable, onDragEnd }: any) => (
    <div
      data-testid="map-marker"
      data-lat={position?.lat}
      data-lng={position?.lng}
      data-draggable={String(draggable)}
      onMouseUp={() => onDragEnd?.({ latLng: { lat: () => 13.0, lng: () => 78.0 } })}
    />
  ),
  Autocomplete: ({ children }: any) => (
    <div data-testid="autocomplete">{children}</div>
  ),
}))

import { useJsApiLoader } from '@react-google-maps/api'

const mockOnChange = vi.fn()

describe('TempleLocationPicker', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.unstubAllEnvs()
  })

  it('should_render_fallback_when_api_key_missing', () => {
    // .env ships a placeholder value for local dev, so it must be stubbed empty here —
    // it is not actually unset in the test environment.
    vi.stubEnv('VITE_GOOGLE_MAPS_API_KEY', '')
    vi.mocked(useJsApiLoader).mockReturnValue({ isLoaded: false, loadError: undefined as any })
    renderWithProviders(
      <TempleLocationPicker lat={null} lng={null} onChange={mockOnChange} />,
    )
    expect(screen.getByText(/Map search unavailable/i)).toBeInTheDocument()
  })

  it('should_render_loading_state_when_maps_not_loaded', () => {
    vi.stubEnv('VITE_GOOGLE_MAPS_API_KEY', 'test-key')
    vi.mocked(useJsApiLoader).mockReturnValue({ isLoaded: false, loadError: undefined as any })
    renderWithProviders(
      <TempleLocationPicker lat={null} lng={null} onChange={mockOnChange} />,
    )
    expect(screen.getByText(/Loading map/i)).toBeInTheDocument()
  })

  it('should_render_existing_coordinates_on_initial_load', () => {
    vi.stubEnv('VITE_GOOGLE_MAPS_API_KEY', 'test-key')
    vi.mocked(useJsApiLoader).mockReturnValue({ isLoaded: true, loadError: undefined as any })
    renderWithProviders(
      <TempleLocationPicker lat={12.9716} lng={77.5946} onChange={mockOnChange} />,
    )
    const marker = screen.getByTestId('map-marker')
    expect(marker).toHaveAttribute('data-lat', '12.9716')
    expect(marker).toHaveAttribute('data-lng', '77.5946')
  })

  it('should_update_coordinates_when_marker_dragged', () => {
    vi.stubEnv('VITE_GOOGLE_MAPS_API_KEY', 'test-key')
    vi.mocked(useJsApiLoader).mockReturnValue({ isLoaded: true, loadError: undefined as any })
    renderWithProviders(
      <TempleLocationPicker lat={12.9716} lng={77.5946} onChange={mockOnChange} />,
    )
    const marker = screen.getByTestId('map-marker')
    marker.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
    expect(mockOnChange).toHaveBeenCalledWith(
      expect.objectContaining({ lat: 13.0, lng: 78.0 }),
    )
  })

  it('should_render_fallback_text_when_no_api_key', () => {
    vi.stubEnv('VITE_GOOGLE_MAPS_API_KEY', '')
    vi.mocked(useJsApiLoader).mockReturnValue({ isLoaded: false, loadError: undefined as any })
    renderWithProviders(
      <TempleLocationPicker lat={12.9716} lng={77.5946} onChange={mockOnChange} />,
    )
    expect(screen.getByText(/Map search unavailable/i)).toBeInTheDocument()
  })
})
