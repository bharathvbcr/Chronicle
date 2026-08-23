import { describe, expect, it } from 'vitest'
import {
  addDays,
  avgMoodFace,
  entryDayKey,
  monthGrid,
  weekStartMonday,
} from './timelinePeriod'

describe('entryDayKey', () => {
  it('uses offset wall-date, not browser-local Date conversion', () => {
    // Evening Pacific — in many eastern TZs this Instant is already next calendar day.
    const ts = '2026-01-15T23:30:00-08:00'
    const browserLocal = new Date(ts)
    // Sanity: Date's local Y-M-D often disagrees with the offset wall-date.
    const localKey = `${browserLocal.getFullYear()}-${String(browserLocal.getMonth() + 1).padStart(2, '0')}-${String(browserLocal.getDate()).padStart(2, '0')}`
    expect(entryDayKey(ts)).toBe('2026-01-15')
    // Document the regression class: if TZ makes them equal, still assert offset path.
    if (localKey !== '2026-01-15') {
      expect(entryDayKey(ts)).not.toBe(localKey)
    }
  })

  it('handles Z and +HH:MM offsets', () => {
    expect(entryDayKey('2026-07-09T01:00:00Z')).toBe('2026-07-09')
    expect(entryDayKey('2026-07-09T09:00:15+05:30')).toBe('2026-07-09')
    expect(entryDayKey('2026-07-08T23:00:10+0530')).toBe('2026-07-08')
  })

  it('falls back to entry id date for naive/malformed ts', () => {
    expect(entryDayKey('2026-07-09T12:00:00', '2026-07-09_120000-pc')).toBe('2026-07-09')
    expect(entryDayKey('not-a-date', '2026-07-08_230010-an')).toBe('2026-07-08')
    expect(entryDayKey('garbage')).toBe('unknown')
  })
})

describe('weekStartMonday', () => {
  it('returns Monday for mid-week and Sunday', () => {
    expect(weekStartMonday('2026-07-15')).toBe('2026-07-13') // Wed
    expect(weekStartMonday('2026-07-13')).toBe('2026-07-13') // Mon
    expect(weekStartMonday('2026-07-19')).toBe('2026-07-13') // Sun
  })
})

describe('monthGrid', () => {
  it('builds Mon–Sun weeks with out-of-month padding', () => {
    // July 2026 starts on Wednesday
    const weeks = monthGrid(2026, 7)
    expect(weeks[0][0]).toEqual({ dayKey: '2026-06-29', inMonth: false })
    expect(weeks[0][2]).toEqual({ dayKey: '2026-07-01', inMonth: true })
    expect(weeks[0][6].dayKey).toBe('2026-07-05')
    const flat = weeks.flat()
    expect(flat.filter((c) => c.inMonth).length).toBe(31)
    expect(weeks.every((w) => w.length === 7)).toBe(true)
  })
})

describe('avgMoodFace', () => {
  it('averages to nearest face and ignores nulls', () => {
    expect(avgMoodFace([1, 5])).toBe('😐') // avg 3
    expect(avgMoodFace([5, 5, null, undefined])).toBe('🌟')
    expect(avgMoodFace([null, undefined])).toBeNull()
    expect(avgMoodFace([])).toBeNull()
  })
})

describe('addDays', () => {
  it('crosses month boundaries', () => {
    expect(addDays('2026-07-31', 1)).toBe('2026-08-01')
    expect(addDays('2026-07-13', 6)).toBe('2026-07-19')
  })
})
