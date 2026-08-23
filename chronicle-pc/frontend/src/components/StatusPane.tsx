import type { ReactNode } from 'react'

interface StatusPaneProps {
  loading?: boolean
  error?: string | null
  empty?: boolean
  emptyMessage?: string
  loadingMessage?: string
  className?: string
  children?: ReactNode
  /** Skeleton rows while loading (defaults to 4). */
  skeletonCount?: number
  /** Optional empty-state call to action. */
  emptyAction?: {
    label: string
    onClick: () => void
  }
  /** Optional retry for error state. */
  onRetry?: () => void
  retryLabel?: string
}

/** Shared loading / empty / error surface for list and detail panes. */
export function StatusPane({
  loading,
  error,
  empty,
  emptyMessage = 'Nothing here yet.',
  loadingMessage = 'Loading…',
  className,
  children,
  skeletonCount = 4,
  emptyAction,
  onRetry,
  retryLabel = 'Retry',
}: StatusPaneProps) {
  const cls = className ? ` ${className}` : ''

  if (loading) {
    return (
      <div className={`status-pane status-pane-loading${cls}`} role="status" aria-busy="true">
        <span className="visually-hidden">{loadingMessage}</span>
        <div className="status-skeleton" aria-hidden="true">
          {Array.from({ length: skeletonCount }, (_, i) => (
            <span
              key={i}
              className="skeleton skeleton-block"
              style={{ width: `${88 - (i % 3) * 12}%` }}
            />
          ))}
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div className={`status-pane status-pane-error${cls}`} role="alert">
        <p style={{ color: 'var(--danger)', margin: 0 }}>{error}</p>
        {onRetry ? (
          <button type="button" className="btn" onClick={onRetry} style={{ marginTop: '0.65rem' }}>
            {retryLabel}
          </button>
        ) : null}
      </div>
    )
  }

  if (empty) {
    return (
      <div className={`status-pane status-pane-empty${cls}`}>
        <p className="muted" style={{ margin: 0 }}>
          {emptyMessage}
        </p>
        {emptyAction ? (
          <button
            type="button"
            className="btn primary"
            onClick={emptyAction.onClick}
            style={{ marginTop: '0.75rem' }}
          >
            {emptyAction.label}
          </button>
        ) : null}
      </div>
    )
  }

  return children ? <>{children}</> : null
}
