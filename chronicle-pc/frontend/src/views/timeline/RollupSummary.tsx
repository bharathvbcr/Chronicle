import { useEffect, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { ApiError, api } from '../../api/client'
import { parseFrontmatter } from '../../notes/frontmatter'
import { markdownLinkComponents } from '../../notes/safeMarkdownLink'

interface RollupSummaryProps {
  kind: 'weekly' | 'monthly'
  /** Monday YYYY-MM-DD for weekly; YYYY-MM for monthly. */
  periodKey: string
}

export function RollupSummary({ kind, periodKey }: RollupSummaryProps) {
  const [body, setBody] = useState<string | null>(null)
  const [missing, setMissing] = useState(false)
  const [open, setOpen] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const ac = new AbortController()
    setBody(null)
    setMissing(false)
    setError(null)

    const derived =
      kind === 'weekly'
        ? `_system/derived/weekly/${periodKey}.md`
        : `_system/derived/monthly/${periodKey}.md`
    const legacy =
      kind === 'weekly' ? `notes/weekly/${periodKey}.md` : `notes/monthly/${periodKey}.md`

    void (async () => {
      try {
        const note = await loadFirstNote([derived, legacy], ac.signal)
        if (ac.signal.aborted) return
        if (!note) {
          setMissing(true)
          return
        }
        const { body: md } = parseFrontmatter(note)
        setBody(md.trim() ? md.trimStart() : '')
      } catch (e) {
        if (e instanceof DOMException && e.name === 'AbortError') return
        setError(e instanceof Error ? e.message : String(e))
      }
    })()

    return () => ac.abort()
  }, [kind, periodKey])

  if (error) {
    return (
      <div className="rollup-summary soft-fail">
        <p className="muted">Couldn’t load {kind} summary.</p>
      </div>
    )
  }

  if (missing) {
    return (
      <div className="rollup-summary soft-fail">
        <p className="muted">
          No {kind} rollup yet. Run <code>chronicle rollup</code> after processing.
        </p>
      </div>
    )
  }

  if (body == null) {
    return (
      <div className="rollup-summary">
        <p className="muted">Loading summary…</p>
      </div>
    )
  }

  const title = kind === 'weekly' ? 'Week summary' : 'Month summary'

  return (
    <div className="rollup-summary">
      <button
        type="button"
        className="rollup-summary-toggle"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <span>{title}</span>
        <span className="muted" aria-hidden>
          {open ? '▾' : '▸'}
        </span>
      </button>
      {open ? (
        <div className="rollup-summary-body">
          {body ? (
            <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownLinkComponents()}>
              {body}
            </ReactMarkdown>
          ) : (
            <p className="muted">Empty summary.</p>
          )}
        </div>
      ) : null}
    </div>
  )
}

async function loadFirstNote(paths: string[], signal: AbortSignal): Promise<string | null> {
  for (const path of paths) {
    try {
      const note = await api.notes.get(path, { signal })
      return note.content
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') throw e
      if (e instanceof ApiError && e.status === 404) continue
      throw e
    }
  }
  return null
}
