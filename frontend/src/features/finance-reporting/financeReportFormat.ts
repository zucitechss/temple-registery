/**
 * Formatting for finance reporting figures (FIN-091).
 *
 * <p>Deliberately never called with a null value — every caller branches on
 * `MetricEnvelope.availability` first and renders {@link AvailabilityNotice} instead. A formatter
 * that silently turned `null` into `'—'` would make "unavailable" and "a genuinely small number"
 * look the same on screen, which is the ADR-007 failure this whole feature exists to avoid.
 */

/** `₹1.25Cr` style, for KPI cards and chart axes where space is tight. */
export function formatRupeesCompact(value: number): string {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    notation: 'compact',
    maximumFractionDigits: 2,
  }).format(value)
}

/** `₹90,61,62,936` — full precision, for tooltips and detail views. */
export function formatRupeesFull(value: number): string {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 2,
  }).format(value)
}

/** Plain integer grouping for a receipt count — never a currency, never a devotee count. */
export function formatReceiptCount(value: number): string {
  return new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 }).format(value)
}
