/**
 * The Indian financial year a date falls in, in canonical form (FIN-091).
 *
 * <p>Client-side mirror of the backend's `FinancialYear.of` (1 April – 31 March). Used only to
 * pick a sensible default year to request; the backend is the sole authority on what a financial
 * year contains or when one is closed.
 */
export function currentFinancialYear(asOf: Date = new Date()): string {
  const month = asOf.getMonth() + 1 // 1-12
  const startYear = month >= 4 ? asOf.getFullYear() : asOf.getFullYear() - 1
  const endYy = (startYear + 1) % 100
  return `${startYear}-${String(endYy).padStart(2, '0')}`
}
