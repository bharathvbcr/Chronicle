import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from '../api/client'
import type { Entry, EntryType } from '../api/types'
import { StatusPane } from '../components/StatusPane'
import { moodFace } from '../lib/moodFaces'
import { isEntryLocked } from '../lib/e2ee'
import {
  addDays,
  dayInInclusiveRange,
  entryDayKey,
  monthBounds,
  parseLocalDay,
  todayDayKey,
  weekStartMonday,
} from '../lib/timelinePeriod'
import { MonthCalendarGrid } from './timeline/MonthCalendarGrid'
import { RollupSummary } from './timeline/RollupSummary'
import { TimelineChrome, type TimelineMode } from './timeline/TimelineChrome'
import { WeekDayStrip } from './timeline/WeekDayStrip'
import './TimelineView.css'

const TYPE_FILTERS: Array<EntryType | 'all'> = ['all', 'log', 'idea', 'dream', 'reflection']
const DAY_LIMIT = 200
const PERIOD_LIMIT = 1000

interface TimelineViewProps {
  selectedId: string | null
  onSelect: (entry: Entry) => void
  onVisibleIds?: (ids: string[]) => void
  refreshKey?: number
  onEmptyCreate?: () => void
}

export function TimelineView({
  selectedId,
  onSelect,
  onVisibleIds,
  refreshKey = 0,
  onEmptyCreate,
}: TimelineViewProps) {
  const [mode, setMode] = useState<TimelineMode>('day')
  const [anchorDay, setAnchorDay] = useState(() => todayDayKey())
  const [selectedDay, setSelectedDay] = useState<string | null>(null)
  const [entries, setEntries] = useState<Entry[]>([])
  const [total, setTotal] = useState(0)
  const [fetchLimit, setFetchLimit] = useState(DAY_LIMIT)
  const [filter, setFilter] = useState<EntryType | 'all'>('all')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  const weekStart = useMemo(() => weekStartMonday(anchorDay), [anchorDay])
  const weekEnd = useMemo(() => addDays(weekStart, 6), [weekStart])
  const monthParts = useMemo(() => {
    const d = parseLocalDay(anchorDay)
    if (!d) return { year: 2026, month: 1 }
    return { year: d.getFullYear(), month: d.getMonth() + 1 }
  }, [anchorDay])
  const monthRange = useMemo(
    () => monthBounds(monthParts.year, monthParts.month),
    [monthParts.year, monthParts.month],
  )

  const fetchRange = useMemo(() => {
    if (mode === 'day') return null
    if (mode === 'week') return { from: weekStart, to: weekEnd }
    // Include ±7 days so out-of-month padding cells can show mood when entries exist
    return { from: addDays(monthRange.from, -7), to: addDays(monthRange.to, 7) }
  }, [mode, weekStart, weekEnd, monthRange.from, monthRange.to])

  const periodLabel = useMemo(() => {
    if (mode === 'week') {
      return `${formatPeriodDay(weekStart)} – ${formatPeriodDay(weekEnd)}`
    }
    if (mode === 'month') {
      const d = parseLocalDay(monthRange.from)
      if (!d) return monthRange.from.slice(0, 7)
      return d.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
    }
    return null
  }, [mode, weekStart, weekEnd, monthRange.from])

  const periodContainsDay = useCallback(
    (dayKey: string) => {
      if (dayKey === 'unknown') return false
      if (mode === 'week') return dayInInclusiveRange(dayKey, weekStart, weekEnd)
      if (mode === 'month') return dayInInclusiveRange(dayKey, monthRange.from, monthRange.to)
      return true
    },
    [mode, weekStart, weekEnd, monthRange.from, monthRange.to],
  )

  const load = useCallback(
    async (signal?: AbortSignal) => {
      setLoading(true)
      setError(null)
      try {
        const limit = mode === 'day' ? DAY_LIMIT : PERIOD_LIMIT
        const page = await api.entries.list(
          mode === 'day' || !fetchRange
            ? { limit }
            : { limit, from: fetchRange.from, to: fetchRange.to },
          { signal },
        )
        if (signal?.aborted) return
        setEntries(page.entries)
        setTotal(page.total)
        setFetchLimit(page.limit)
      } catch (e) {
        if (e instanceof DOMException && e.name === 'AbortError') return
        setError(e instanceof Error ? e.message : String(e))
      } finally {
        if (!signal?.aborted) setLoading(false)
      }
    },
    [mode, fetchRange],
  )

  useEffect(() => {
    const ac = new AbortController()
    void load(ac.signal)
    return () => ac.abort()
  }, [load, refreshKey])

  useEffect(() => {
    if (selectedDay && !periodContainsDay(selectedDay)) {
      setSelectedDay(null)
    }
  }, [selectedDay, periodContainsDay])

  const handleModeChange = (next: TimelineMode) => {
    setMode(next)
    if (next === 'day') setSelectedDay(null)
    // Week/Month: keep selectedDay when still in period (cleared by effect otherwise).
  }

  const handlePrevPeriod = () => {
    if (mode === 'week') setAnchorDay(addDays(weekStart, -7))
    else if (mode === 'month') setAnchorDay(addDays(monthRange.from, -1))
  }

  const handleNextPeriod = () => {
    if (mode === 'week') setAnchorDay(addDays(weekStart, 7))
    else if (mode === 'month') setAnchorDay(addDays(monthRange.to, 1))
  }

  const handleSelectDay = (dayKey: string) => {
    // Orthogonal to entry onSelect — never open EntryModal from day taps.
    setSelectedDay((prev) => (prev === dayKey ? null : dayKey))
    setAnchorDay(dayKey)
  }

  const typeFiltered = useMemo(
    () => (filter === 'all' ? entries : entries.filter((e) => e.type === filter)),
    [entries, filter],
  )

  const entriesByDay = useMemo(() => {
    const map = new Map<string, Entry[]>()
    for (const e of typeFiltered) {
      const key = entryDayKey(e.ts, e.id)
      const list = map.get(key)
      if (list) list.push(e)
      else map.set(key, [e])
    }
    return map
  }, [typeFiltered])

  const listEntries = useMemo(() => {
    if (mode === 'day') return typeFiltered
    if (selectedDay) {
      return typeFiltered.filter((e) => entryDayKey(e.ts, e.id) === selectedDay)
    }
    if (mode === 'week') {
      return typeFiltered.filter((e) => {
        const k = entryDayKey(e.ts, e.id)
        return dayInInclusiveRange(k, weekStart, weekEnd)
      })
    }
    // Month: in-month days only for the list (padding moods stay on grid)
    return typeFiltered.filter((e) => {
      const k = entryDayKey(e.ts, e.id)
      return dayInInclusiveRange(k, monthRange.from, monthRange.to)
    })
  }, [mode, typeFiltered, selectedDay, weekStart, weekEnd, monthRange.from, monthRange.to])

  const groups = useMemo(() => {
    if (mode === 'month' && !selectedDay) {
      return groupByWeekThenDay(listEntries)
    }
    return groupByDay(listEntries)
  }, [mode, selectedDay, listEntries])

  useEffect(() => {
    onVisibleIds?.(listEntries.map((e) => e.id))
  }, [listEntries, onVisibleIds])

  const selected = listEntries.find((e) => e.id === selectedId) ?? null
  const incomplete = mode !== 'day' && total > fetchLimit
  const emptyPeriod = !loading && !error && listEntries.length === 0

  return (
    <div className="timeline">
      <TimelineChrome
        mode={mode}
        onModeChange={handleModeChange}
        periodLabel={periodLabel}
        onPrevPeriod={handlePrevPeriod}
        onNextPeriod={handleNextPeriod}
      />
      <div className="timeline-body">
        <aside className="timeline-list glass">
          <div className="timeline-filters">
            {TYPE_FILTERS.map((t) => (
              <button
                key={t}
                type="button"
                className={`chip${filter === t ? ' active' : ''}`}
                aria-pressed={filter === t}
                onClick={() => setFilter(t)}
              >
                {t}
              </button>
            ))}
          </div>
          {mode === 'week' ? (
            <WeekDayStrip
              weekStart={weekStart}
              entriesByDay={entriesByDay}
              selectedDay={selectedDay}
              onSelectDay={handleSelectDay}
            />
          ) : null}
          {mode === 'month' ? (
            <MonthCalendarGrid
              year={monthParts.year}
              month={monthParts.month}
              entriesByDay={entriesByDay}
              selectedDay={selectedDay}
              onSelectDay={handleSelectDay}
            />
          ) : null}
          {mode === 'week' ? <RollupSummary kind="weekly" periodKey={weekStart} /> : null}
          {mode === 'month' ? (
            <RollupSummary
              kind="monthly"
              periodKey={`${monthParts.year}-${String(monthParts.month).padStart(2, '0')}`}
            />
          ) : null}
          {incomplete ? (
            <p className="timeline-incomplete muted">
              Showing {fetchLimit} of {total} entries in this period.
            </p>
          ) : null}
          <StatusPane
            loading={loading}
            error={error}
            empty={emptyPeriod}
            emptyMessage={
              mode === 'day'
                ? 'No entries yet. Capture a moment from Mac.'
                : selectedDay
                  ? 'No entries on this day.'
                  : 'No entries in this period.'
            }
            emptyAction={
              onEmptyCreate && mode === 'day'
                ? { label: 'Add entry', onClick: onEmptyCreate }
                : undefined
            }
            onRetry={() => void load()}
            className="pad"
          >
            <div className="timeline-groups">
              {groups.map((group) => (
                <section key={group.key} className="timeline-day">
                  {group.weekLabel ? (
                    <header className="timeline-week-header">{group.weekLabel}</header>
                  ) : null}
                  <header className="timeline-day-header">{group.label}</header>
                  <ul>
                    {group.entries.map((e) => {
                      const face = moodFace(e.mood)
                      return (
                        <li key={e.id}>
                          <button
                            type="button"
                            className={`entry-row type-edge type-edge-${e.type}${selected?.id === e.id ? ' selected' : ''}`}
                            onClick={() => onSelect(e)}
                          >
                            <span className={`type-dot type-${e.type}`} />
                            <span className="entry-meta">
                              <span className="entry-when">
                                {formatWhen(e.ts)}
                                {e.processed ? ' · ✓' : ''}
                                {e.filed ? ' · filed' : ''}
                                {isEntryLocked(e) ? ' · 🔒' : ''}
                              </span>
                              <span className="entry-preview">
                                {isEntryLocked(e)
                                  ? '🔒 Encrypted — unlock in Settings'
                                  : e.filed
                                    ? `(filed · structured) ${e.text.slice(0, 80) || 'see 40-Journal'}`
                                    : e.text.slice(0, 100) || '(empty)'}
                              </span>
                            </span>
                            {face ? (
                              <span className="mood-chip" title={`Mood ${e.mood}`}>
                                {face}
                              </span>
                            ) : null}
                          </button>
                        </li>
                      )
                    })}
                  </ul>
                </section>
              ))}
            </div>
          </StatusPane>
        </aside>
        <section className="timeline-detail glass">
          {selected ? (
            <>
              <header>
                <h2 className="serif">{selected.type}</h2>
                <p className="muted">
                  {selected.id} · {formatWhen(selected.ts)}
                  {selected.filed ? ' · prose in 40-Journal' : ''}
                </p>
                <div className="tag-row">
                  {(selected.tags || []).map((t) => (
                    <span key={t} className="tag">
                      {t}
                    </span>
                  ))}
                  {selected.mood != null ? (
                    <span className="tag mood-chip">
                      {moodFace(selected.mood)} mood {selected.mood}
                    </span>
                  ) : null}
                  {selected.filed_path ? (
                    <span className="tag">{selected.filed_path}</span>
                  ) : null}
                </div>
              </header>
              <article className="entry-body">
                {isEntryLocked(selected) ? (
                  <p className="muted">
                    🔒 This entry is encrypted and the vault is locked. Unlock in
                    Settings → Encryption to read it.
                  </p>
                ) : selected.filed ? (
                  <>
                    <p className="muted">
                      Structured fields (mood/tags/type) stay in JSON. Prose SoT is the journal
                      fence{selected.filed_path ? ` at ${selected.filed_path}` : ''}. JSON text below
                      is frozen provenance.
                    </p>
                    {selected.text || <em className="muted">No provenance text</em>}
                  </>
                ) : (
                  selected.text || <em className="muted">No text</em>
                )}
              </article>
              <footer>
                <button type="button" className="btn primary" onClick={() => onSelect(selected)}>
                  Open
                </button>
              </footer>
            </>
          ) : (
            <p className="muted pad">
              {loading
                ? 'Loading…'
                : listEntries.length === 0
                  ? mode === 'day'
                    ? 'No entries yet. Press n to add one from Mac.'
                    : 'Pick a day or capture an entry.'
                  : 'Select an entry, or use j/k to move.'}
            </p>
          )}
        </section>
      </div>
    </div>
  )
}

type DayGroup = {
  key: string
  label: string
  weekLabel?: string
  entries: Entry[]
}

function groupByDay(entries: Entry[]): DayGroup[] {
  const map = new Map<string, Entry[]>()
  for (const e of entries) {
    const key = entryDayKey(e.ts, e.id)
    const list = map.get(key)
    if (list) list.push(e)
    else map.set(key, [e])
  }
  return Array.from(map.entries()).map(([key, list]) => ({
    key,
    label: dayLabel(key),
    entries: list,
  }))
}

function groupByWeekThenDay(entries: Entry[]): DayGroup[] {
  const byWeek = new Map<string, Entry[]>()
  for (const e of entries) {
    const day = entryDayKey(e.ts, e.id)
    const week = day === 'unknown' ? 'unknown' : weekStartMonday(day)
    const list = byWeek.get(week)
    if (list) list.push(e)
    else byWeek.set(week, [e])
  }
  const out: DayGroup[] = []
  const weeks = Array.from(byWeek.keys()).sort((a, b) => b.localeCompare(a))
  for (const week of weeks) {
    const dayGroups = groupByDay(byWeek.get(week)!)
    dayGroups.forEach((g, i) => {
      out.push({
        ...g,
        key: `${week}:${g.key}`,
        weekLabel: i === 0 && week !== 'unknown' ? `Week of ${formatPeriodDay(week)}` : undefined,
      })
    })
  }
  return out
}

function dayLabel(key: string): string {
  if (key === 'unknown') return 'Unknown date'
  try {
    const [y, m, d] = key.split('-').map(Number)
    const date = new Date(y, m - 1, d)
    const today = new Date()
    const yesterday = new Date()
    yesterday.setDate(today.getDate() - 1)
    if (sameDay(date, today)) return 'Today'
    if (sameDay(date, yesterday)) return 'Yesterday'
    return date.toLocaleDateString(undefined, {
      weekday: 'short',
      month: 'short',
      day: 'numeric',
      year: date.getFullYear() !== today.getFullYear() ? 'numeric' : undefined,
    })
  } catch {
    return key
  }
}

function formatPeriodDay(key: string): string {
  const d = parseLocalDay(key)
  if (!d) return key
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

function sameDay(a: Date, b: Date): boolean {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  )
}

function formatWhen(ts: string): string {
  try {
    return new Date(ts).toLocaleString(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return ts
  }
}
