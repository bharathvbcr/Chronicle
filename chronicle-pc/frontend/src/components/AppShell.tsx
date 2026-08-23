import { useEffect, useState, type ReactNode } from 'react'
import { NavLink, Outlet } from 'react-router-dom'
import { useTheme } from '../hooks/useTheme'
import {
  IconBrain,
  IconNotes,
  IconPlus,
  IconSearch,
  IconSettings,
  IconTheme,
  IconTimeline,
} from './icons'
import './AppShell.css'

const NAV: Array<{ to: string; label: string; end?: boolean; icon: ReactNode }> = [
  { to: '/', label: 'Timeline', end: true, icon: <IconTimeline /> },
  { to: '/vault', label: 'Notes', icon: <IconNotes /> },
  { to: '/brain', label: 'Brain', icon: <IconBrain /> },
  { to: '/settings', label: 'Settings', icon: <IconSettings /> },
]

interface AppShellProps {
  onSearch: () => void
  onNewEntry: () => void
  status?: string
  /** SSE connection state — renders a live dot next to the brand when live. */
  liveState?: 'connecting' | 'live' | 'offline'
}

const LIVE_LABEL: Record<NonNullable<AppShellProps['liveState']>, string> = {
  connecting: 'Connecting for live updates',
  live: 'Live — changes from phone/pipeline appear automatically',
  offline: 'Live updates unavailable (will keep retrying)',
}

export function AppShell({ onSearch, onNewEntry, status, liveState }: AppShellProps) {
  const theme = useTheme()
  const [toast, setToast] = useState<string | null>(null)

  useEffect(() => {
    if (!status) return
    setToast(status)
    const t = window.setTimeout(() => setToast(null), 3200)
    return () => window.clearTimeout(t)
  }, [status])

  return (
    <div className="app-shell">
      <header className="appbar">
        <div className="brand serif" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <img src="/logo.png" alt="Chronicle logo" style={{ height: '24px', width: '24px', objectFit: 'contain' }} />
          <span>Chron<span>icle</span></span>
          {liveState ? (
            <span
              className={`live-dot live-${liveState}`}
              role="status"
              aria-label={LIVE_LABEL[liveState]}
              title={LIVE_LABEL[liveState]}
            />
          ) : null}
        </div>
        <nav className="tabs" aria-label="Main">
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) => (isActive ? 'active' : undefined)}
            >
              <span className="tab-icon">{item.icon}</span>
              <span className="tab-label">{item.label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="spacer" />
        <button type="button" className="btn ghost btn-icon" onClick={onSearch} title="Search (/)">
          <IconSearch />
          <span>Search</span>
        </button>
        <button type="button" className="btn primary btn-icon" onClick={onNewEntry} title="New entry (n)">
          <IconPlus />
          <span>Add</span>
        </button>
        <button
          type="button"
          className="btn btn-theme btn-icon"
          onClick={theme.cycle}
          title={`Theme: ${theme.label}`}
          aria-label={`Theme ${theme.label}`}
        >
          <IconTheme />
          <span className="theme-label">{theme.icon}</span>
        </button>
      </header>
      <main className="app-main route-fade">
        <Outlet />
      </main>
      <div
        className={`app-toast${toast ? ' open' : ''}`}
        role="status"
        aria-live="polite"
        aria-atomic="true"
      >
        {toast}
      </div>
    </div>
  )
}
