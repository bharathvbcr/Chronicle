import { useEffect, useRef, type RefObject } from 'react'

const FOCUSABLE =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'

/** Trap focus inside `containerRef` while `active`, and restore focus on deactivate. */
export function useFocusTrap(active: boolean, containerRef: RefObject<HTMLElement | null>) {
  const previousFocus = useRef<HTMLElement | null>(null)

  useEffect(() => {
    if (!active) return

    previousFocus.current =
      document.activeElement instanceof HTMLElement ? document.activeElement : null

    const container = containerRef.current
    const focusables = () =>
      container
        ? (Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
            (el) => !el.hasAttribute('disabled') && el.tabIndex !== -1,
          ) as HTMLElement[])
        : []

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Tab' || !container) return
      const items = focusables()
      if (items.length === 0) {
        e.preventDefault()
        return
      }
      const first = items[0]
      const last = items[items.length - 1]
      const current = document.activeElement
      if (e.shiftKey) {
        if (current === first || !container.contains(current)) {
          e.preventDefault()
          last.focus()
        }
      } else if (current === last || !container.contains(current)) {
        e.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      previousFocus.current?.focus?.()
      previousFocus.current = null
    }
  }, [active, containerRef])
}
