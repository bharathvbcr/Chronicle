import { avgMoodFace, monthGrid } from '../../lib/timelinePeriod'
import type { Entry } from '../../api/types'

interface MonthCalendarGridProps {
  year: number
  month: number
  entriesByDay: Map<string, Entry[]>
  selectedDay: string | null
  onSelectDay: (dayKey: string) => void
}

const DOW = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

export function MonthCalendarGrid({
  year,
  month,
  entriesByDay,
  selectedDay,
  onSelectDay,
}: MonthCalendarGridProps) {
  const weeks = monthGrid(year, month)

  return (
    <div className="month-calendar" aria-label={`Calendar ${year}-${String(month).padStart(2, '0')}`}>
      <div className="month-calendar-dow">
        {DOW.map((d) => (
          <span key={d}>{d}</span>
        ))}
      </div>
      <div className="month-calendar-weeks">
        {weeks.map((week) => (
          <div key={week[0].dayKey} className="month-calendar-week">
            {week.map((cell) => {
              const dayEntries = entriesByDay.get(cell.dayKey) ?? []
              const face = avgMoodFace(dayEntries.map((e) => e.mood))
              const selected = selectedDay === cell.dayKey
              return (
                <button
                  key={cell.dayKey}
                  type="button"
                  className={`month-day-cell${cell.inMonth ? '' : ' out-of-month'}${selected ? ' selected' : ''}${dayEntries.length === 0 ? ' empty' : ''}`}
                  aria-pressed={selected}
                  aria-label={cell.dayKey}
                  onClick={() => onSelectDay(cell.dayKey)}
                >
                  <span className="month-day-num">{Number(cell.dayKey.slice(8))}</span>
                  <span className="month-day-mood" aria-hidden>
                    {face ? (
                      face
                    ) : dayEntries.length > 0 ? (
                      <span className="entry-count-dot" />
                    ) : null}
                  </span>
                </button>
              )
            })}
          </div>
        ))}
      </div>
    </div>
  )
}
