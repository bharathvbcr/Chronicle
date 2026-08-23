import { useEffect } from 'react'

function isTypingTarget(el: EventTarget | null): boolean {
  if (!(el instanceof HTMLElement)) return false
  const tag = el.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable
}

export interface ShortcutHandlers {
  onSearch?: () => void
  onNewEntry?: () => void
  onEscape?: () => void
  onTimelineNav?: (dir: 1 | -1) => void
  onEnter?: () => void
}

/** Global shortcuts matching the classic dashboard spirit: / search, n entry, j/k nav, Esc. */
export function useKeyboardShortcuts(handlers: ShortcutHandlers, enabled = true) {
  useEffect(() => {
    if (!enabled) return

    const onKey = (e: KeyboardEvent) => {
      const typing = isTypingTarget(e.target)

      if (e.key === 'Escape') {
        handlers.onEscape?.()
        return
      }

      if (!typing && e.key === '/') {
        e.preventDefault()
        handlers.onSearch?.()
        return
      }

      if (!typing && e.key === 'n') {
        e.preventDefault()
        handlers.onNewEntry?.()
        return
      }

      if (typing) return

      if (e.key === 'j') handlers.onTimelineNav?.(1)
      if (e.key === 'k') handlers.onTimelineNav?.(-1)
      if (e.key === 'Enter') handlers.onEnter?.()
    }

    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [handlers, enabled])
}
