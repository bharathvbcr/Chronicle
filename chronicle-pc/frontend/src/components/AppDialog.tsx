import { useEffect, useId, useRef } from 'react'
import { useFocusTrap } from '../hooks/useFocusTrap'

interface AppDialogProps {
  open: boolean
  title: string
  message?: string
  confirmLabel?: string
  cancelLabel?: string
  danger?: boolean
  /** When set, shows a text field (prompt-style). */
  prompt?: {
    label?: string
    defaultValue?: string
    placeholder?: string
  }
  onConfirm: (value?: string) => void
  onCancel: () => void
}

/** In-app confirm / prompt dialog (replaces window.confirm / window.prompt). */
export function AppDialog({
  open,
  title,
  message,
  confirmLabel = 'OK',
  cancelLabel = 'Cancel',
  danger,
  prompt,
  onConfirm,
  onCancel,
}: AppDialogProps) {
  const panelRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const titleId = useId()
  useFocusTrap(open, panelRef)

  useEffect(() => {
    if (!open) return
    requestAnimationFrame(() => {
      if (prompt) inputRef.current?.focus()
      else panelRef.current?.querySelector<HTMLElement>('button.btn.primary, button.btn.danger')?.focus()
    })
  }, [open, prompt])

  if (!open) return null

  return (
    <div
      className="overlay open"
      role="presentation"
      onClick={(e) => {
        if (e.target === e.currentTarget) onCancel()
      }}
    >
      <div
        ref={panelRef}
        className="overlay-panel glass app-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
      >
        <h2 id={titleId} className="serif" style={{ margin: '0 0 0.5rem', fontSize: '1.1rem' }}>
          {title}
        </h2>
        {message ? <p className="muted" style={{ marginTop: 0 }}>{message}</p> : null}
        {prompt ? (
          <label className="app-dialog-field">
            {prompt.label ? <span className="muted">{prompt.label}</span> : null}
            <input
              ref={inputRef}
              className="field"
              defaultValue={prompt.defaultValue ?? ''}
              placeholder={prompt.placeholder}
              aria-label={prompt.label || title}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault()
                  onConfirm(inputRef.current?.value)
                }
                if (e.key === 'Escape') {
                  e.preventDefault()
                  onCancel()
                }
              }}
            />
          </label>
        ) : null}
        <div className="app-dialog-actions">
          <button type="button" className="btn" onClick={onCancel}>
            {cancelLabel}
          </button>
          <button
            type="button"
            className={`btn${danger ? ' danger' : ' primary'}`}
            onClick={() => onConfirm(prompt ? inputRef.current?.value : undefined)}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
