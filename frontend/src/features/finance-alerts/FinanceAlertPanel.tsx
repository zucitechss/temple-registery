import type { FinanceAlertPanelProps } from '../finance/financeCoreTypes'

/**
 * Missed daily update alerts (FR17, FR18, FR20).
 *
 * ── PHASE 0 PLACEHOLDER ────────────────────────────────────────────────────
 *
 * This is the single component that crosses between the two build streams. The props are frozen
 * in `financeCoreTypes.ts`; this file is a placeholder so that the DC dashboard can import and
 * place it from day one, and is replaced wholesale by the input stream when the real panel is
 * built. Because the replacement is this file rather than the import site, no caller changes.
 *
 * It renders **nothing** rather than an empty state. An alert panel that says "no alerts" before
 * the feature exists tells a DC user their temples are up to date, which is the one wrong thing
 * it could say. Silence is the honest placeholder.
 *
 * When implementing, replace this file entirely. Keep the props signature; if it needs to change,
 * that is a conversation, not a commit — it is one of only two cross-stream interfaces.
 */
// eslint-disable-next-line @typescript-eslint/no-unused-vars
export function FinanceAlertPanel(_props: FinanceAlertPanelProps) {
  return null
}
