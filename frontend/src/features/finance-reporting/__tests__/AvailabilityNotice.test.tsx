import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AvailabilityNotice } from '../components/AvailabilityNotice'

describe('AvailabilityNotice (FIN-092)', () => {
  it('renders nothing when the metric is AVAILABLE — a present figure needs no notice', () => {
    const { container } = render(<AvailabilityNotice availability="AVAILABLE" reason={null} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders the backend’s own reason verbatim for NOT_AVAILABLE, never inventing one', () => {
    render(
      <AvailabilityNotice
        availability="NOT_AVAILABLE"
        reason="The source system does not record expenditure."
      />,
    )
    expect(screen.getByText(/The source system does not record expenditure\./)).toBeInTheDocument()
    expect(screen.getByText(/Not available\./)).toBeInTheDocument()
  })

  it('labels a floor as Partial, distinct from a full absence', () => {
    render(
      <AvailabilityNotice
        availability="PARTIALLY_AVAILABLE"
        reason="3 underlying record(s) did not record a gross amount; the figure shown is a floor."
      />,
    )
    expect(screen.getByText(/Partial\./)).toBeInTheDocument()
  })

  it('never renders a reason as a fabricated fallback beyond the given null-safe text', () => {
    render(<AvailabilityNotice availability="NOT_AVAILABLE" reason={null} />)
    // No reason supplied: says so plainly rather than making one up.
    expect(screen.getByText(/No reason was given\./)).toBeInTheDocument()
  })

  it('labels NOT_APPLICABLE distinctly from NOT_AVAILABLE', () => {
    render(<AvailabilityNotice availability="NOT_APPLICABLE" reason="This temple has no hall booking service." />)
    expect(screen.getByText(/Not applicable\./)).toBeInTheDocument()
  })
})
