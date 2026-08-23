import { addDays, avgMoodFace } from '../../lib/timelinePeriod'
import type { Entry } from '../../api/types'

interface WeekDayStripProps {
  weekStart: string
  entriesByDay: Map<string, Entry[]>
  selectedDay: string | null
  onSelectDay: (dayKey: string) => void
}

const DOW = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

export function WeekDayStrip({
  weekStart,
  entriesByDay,
  selectedDay,
  onSelectDay,
}: WeekDayStripProps) {
  const days = Array.from({ length: 7 }, (_, i) => addDays(weekStart, i))

  return (
    <div className="week-day-strip" role="list" aria-label="Week days">
      {days.map((dayKey, i) => {
        const dayEntries = entriesByDay.get(dayKey) ?? []
        const face = avgMoodFace(dayEntries.map((e) => e.mood))
        const dayNum = dayKey.slice(8)
        const selected = selectedDay === dayKey
        return (
          <button
            key={dayKey}
            type="button"
            role="listitem"
            className={`week-day-cell${selected ? ' selected' : ''}${dayEntries.length === 0 ? ' empty' : ''}`}
            aria-pressed={selected}
            aria-label={`${DOW[i]} ${dayKey}`}
            onClick={() => onSelectDay(dayKey)}
          >
            <span className="week-day-dow">{DOW[i]}</span>
            <span className="week-day-num">{Number(dayNum)}</span>
            <span className="week-day-mood" aria-hidden>
              {face ? face : dayEntries.length > 0 ? <span className="entry-count-dot" /> : null}
            </span>
          </button>
        )
      })}
    </div>
  )
}
