/**
 * jsdom gaps that Radix primitives rely on.
 *
 * Radix's Select calls pointer-capture and scroll APIs that jsdom does not implement, and
 * ResizeObserver is absent entirely. Same shims as
 * `features/admin/components/UserFormDialog/UserFormDialog.test.tsx`, extracted so the two
 * finance test files share one copy rather than each carrying their own.
 */
export function installRadixJsdomPolyfills() {
  if (typeof window === 'undefined') return

  if (!window.ResizeObserver) {
    window.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver
  }
  if (!window.HTMLElement.prototype.hasPointerCapture) {
    window.HTMLElement.prototype.hasPointerCapture = () => false
  }
  if (!window.HTMLElement.prototype.setPointerCapture) {
    window.HTMLElement.prototype.setPointerCapture = () => {}
  }
  if (!window.HTMLElement.prototype.releasePointerCapture) {
    window.HTMLElement.prototype.releasePointerCapture = () => {}
  }
  if (!window.HTMLElement.prototype.scrollIntoView) {
    window.HTMLElement.prototype.scrollIntoView = () => {}
  }
}
