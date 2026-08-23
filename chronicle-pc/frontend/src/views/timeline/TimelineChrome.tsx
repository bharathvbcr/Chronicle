export type TimelineMode = 'day' | 'week' | 'month'

const MODES: TimelineMode[] = ['day', 'week', 'month']

interface TimelineChromeProps {
  mode: TimelineMode
  onModeChange: (mode: TimelineMode) => void
  periodLabel: string | null
  onPrevPeriod: () => void
  onNextPeriod: () => void
}

export function TimelineChrome({
  mode,
  onModeChange,
  periodLabel,
  onPrevPeriod,
  onNextPeriod,
}: TimelineChromeProps) {
  return (
    <div className="timeline-chrome glass">
      <div className="timeline-mode-pills" role="tablist" aria-label="Timeline period">
        {MODES.map((m) => (
          <button
            key={m}
            type="button"
            role="tab"
            aria-selected={mode === m}
            className={`chip${mode === m ? ' active' : ''}`}
            onClick={() => onModeChange(m)}
          >
            {m === 'day' ? 'Day' : m === 'week' ? 'Week' : 'Month'}
          </button>
        ))}
      </div>
      {mode !== 'day' && periodLabel ? (
        <div className="timeline-period-nav">
          <button
            type="button"
            className="btn ghost timeline-period-btn"
            aria-label="Previous period"
            onClick={onPrevPeriod}
          >
            ←
          </button>
          <span className="timeline-period-label">{periodLabel}</span>
          <button
            type="button"
            className="btn ghost timeline-period-btn"
            aria-label="Next period"
            onClick={onNextPeriod}
          >
            →
          </button>
        </div>
      ) : null}
    </div>
  )
}
