/**
 * Timeline day/period helpers.
 *
 * Day attribution matches CONTRACT / pipeline `entry_day`: calendar Y-M-D of the
 * ISO offset wall-clock (travel-safe). Browser local TZ must not shift the day.
 */

import { moodFace } from './moodFaces'

const OFFSET_TS_RE =
  /^(\d{4}-\d{2}-\d{2})T\d{2}:\d{2}(?::\d{2})?(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})$/
const ID_DAY_RE = /^(\d{4}-\d{2}-\d{2})_/

export type MonthCell = {
  dayKey: string
  inMonth: boolean
}

/** Wall-date day key from ts offset; fallback to entry id date prefix, then unknown. */
export function entryDayKey(ts: string, entryId?: string): string {
  const m = ts.match(OFFSET_TS_RE)
  if (m) return m[1]
  if (entryId) {
    const id = entryId.match(ID_DAY_RE)
    if (id) return id[1]
  }
  return 'unknown'
}

/** Monday (ISO) week start for a YYYY-MM-DD day key. */
export function weekStartMonday(dayKey: string): string {
  const d = parseLocalDay(dayKey)
  if (!d) return dayKey
  const weekday = d.getDay() // 0=Sun … 6=Sat
  const delta = weekday === 0 ? 6 : weekday - 1
  d.setDate(d.getDate() - delta)
  return formatDayKey(d)
}

/** Calendar grid Mon–Sun weeks for month (1–12). Padding cells have inMonth=false. */
export function monthGrid(year: number, month: number): MonthCell[][] {
  const first = new Date(year, month - 1, 1)
  const start = new Date(first)
  const weekday = start.getDay()
  const delta = weekday === 0 ? 6 : weekday - 1
  start.setDate(start.getDate() - delta)

  const weeks: MonthCell[][] = []
  const cursor = new Date(start)
  for (let w = 0; w < 6; w++) {
    const week: MonthCell[] = []
    for (let i = 0; i < 7; i++) {
      week.push({
        dayKey: formatDayKey(cursor),
        inMonth: cursor.getFullYear() === year && cursor.getMonth() === month - 1,
      })
      cursor.setDate(cursor.getDate() + 1)
    }
    weeks.push(week)
    if (week.every((c) => !c.inMonth) && w > 0) {
      weeks.pop()
      break
    }
  }
  while (weeks.length > 0 && weeks[weeks.length - 1].every((c) => !c.inMonth)) {
    weeks.pop()
  }
  return weeks
}

/** Average of moods 1–5 → nearest mood face; null if no moods. */
export function avgMoodFace(moods: Array<number | null | undefined>): string | null {
  const valid: number[] = []
  for (const m of moods) {
    if (m != null && m >= 1 && m <= 5) valid.push(m)
  }
  if (valid.length === 0) return null
  const avg = valid.reduce((a, b) => a + b, 0) / valid.length
  const nearest = Math.min(5, Math.max(1, Math.round(avg)))
  return moodFace(nearest)
}

export function addDays(dayKey: string, days: number): string {
  const d = parseLocalDay(dayKey)
  if (!d) return dayKey
  d.setDate(d.getDate() + days)
  return formatDayKey(d)
}

export function todayDayKey(now: Date = new Date()): string {
  return formatDayKey(now)
}

export function formatDayKey(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

export function parseLocalDay(dayKey: string): Date | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dayKey)
  if (!m) return null
  const y = Number(m[1])
  const mo = Number(m[2])
  const d = Number(m[3])
  const date = new Date(y, mo - 1, d)
  if (date.getFullYear() !== y || date.getMonth() !== mo - 1 || date.getDate() !== d) {
    return null
  }
  return date
}

export function monthBounds(year: number, month: number): { from: string; to: string } {
  const from = formatDayKey(new Date(year, month - 1, 1))
  const to = formatDayKey(new Date(year, month, 0))
  return { from, to }
}

export function dayInInclusiveRange(dayKey: string, from: string, to: string): boolean {
  return dayKey >= from && dayKey <= to
}
