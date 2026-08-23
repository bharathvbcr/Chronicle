import { useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { api } from '../api/client'
import type { Citation, GraphNode, Scope } from '../api/types'
import { IconCopy, IconRetry, IconStop, IconX } from '../components/icons'
import { markdownLinkComponents } from '../notes/safeMarkdownLink'
import { shortNodeId } from './graphUtils'

interface ChatTurn {
  role: 'user' | 'assistant'
  content: string
  citations?: Citation[]
  degraded?: boolean
  error?: boolean
}

const SUGGESTIONS = [
  'Summarize my recent ideas',
  'What connects my current projects?',
  'People and places I mentioned lately',
]

interface RecallChatProps {
  seedNodes: GraphNode[]
  onRemoveSeed: (id: string) => void
  onCitationClick: (citation: Citation) => void
  onHighlight: (nodeIds: string[]) => void
  onPinConcept: (answer: string, seedIds: string[]) => void
}

export function RecallChat({
  seedNodes,
  onRemoveSeed,
  onCitationClick,
  onHighlight,
  onPinConcept,
}: RecallChatProps) {
  const [input, setInput] = useState('')
  const [scope, setScope] = useState<Scope>('all')
  const [turns, setTurns] = useState<ChatTurn[]>([])
  const [busy, setBusy] = useState(false)
  const [copiedIdx, setCopiedIdx] = useState<number | null>(null)
  const logRef = useRef<HTMLDivElement>(null)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    logRef.current?.scrollTo({ top: logRef.current.scrollHeight, behavior: 'smooth' })
  }, [turns, busy])

  useEffect(() => {
    return () => abortRef.current?.abort()
  }, [])

  async function ask(messageText?: string) {
    const message = (messageText ?? input).trim()
    if (!message || busy) return
    setInput('')
    const history = turns.map((t) => ({ role: t.role, content: t.content }))
    setTurns((t) => [...t, { role: 'user', content: message }])
    setBusy(true)
    abortRef.current?.abort()
    const ac = new AbortController()
    abortRef.current = ac
    try {
      const res = await api.recall(
        {
          message,
          history,
          scope,
          node_ids: seedNodes.map((n) => n.id),
        },
        { signal: ac.signal },
      )
      const cited = res.citations.flatMap((c) => c.node_ids || [])
      onHighlight([...new Set([...cited, ...(res.seed_node_ids || [])])])
      setTurns((t) => [
        ...t,
        {
          role: 'assistant',
          content: res.answer,
          citations: res.citations,
          degraded: res.degraded,
        },
      ])
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') return
      setTurns((t) => [
        ...t,
        {
          role: 'assistant',
          content: e instanceof Error ? e.message : String(e),
          degraded: true,
          error: true,
        },
      ])
    } finally {
      if (!ac.signal.aborted) setBusy(false)
    }
  }

  function stop() {
    abortRef.current?.abort()
    setBusy(false)
  }

  function retryFrom(idx: number) {
    for (let j = idx - 1; j >= 0; j--) {
      if (turns[j].role === 'user') {
        void ask(turns[j].content)
        return
      }
    }
  }

  async function copyTurn(content: string, idx: number) {
    try {
      await navigator.clipboard.writeText(content)
    } catch {
      const ta = document.createElement('textarea')
      ta.value = content
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      ta.remove()
    }
    setCopiedIdx(idx)
    window.setTimeout(() => setCopiedIdx((c) => (c === idx ? null : c)), 1500)
  }

  const lastAssistant = [...turns].reverse().find((t) => t.role === 'assistant' && !t.error)

  return (
    <div className="recall-chat glass">
      <header className="recall-header">
        <div className="recall-header-row">
          <h3 className="serif">Recall</h3>
          <select
            className="field scope-select"
            value={scope}
            onChange={(e) => setScope(e.target.value as Scope)}
            aria-label="Recall scope"
          >
            <option value="all">all</option>
            <option value="journal">journal</option>
            <option value="kb">kb</option>
          </select>
        </div>
        <div className="seed-chips">
          {seedNodes.length === 0 ? (
            <span className="muted">No nodes selected — asking across the vault</span>
          ) : (
            seedNodes.map((n) => (
              <span key={n.id} className="seed-chip" title={n.id}>
                {n.label || n.id}
                <button
                  type="button"
                  className="seed-chip-x"
                  onClick={() => onRemoveSeed(n.id)}
                  aria-label={`Remove seed ${n.label || n.id}`}
                >
                  <IconX />
                </button>
              </span>
            ))
          )}
        </div>
      </header>

      <div className="recall-log" ref={logRef} aria-live="polite">
        {turns.length === 0 ? (
          <div className="recall-empty">
            <p className="muted pad-sm">
              Select graph nodes to seed context, then ask. Citations will pulse on the map.
            </p>
            <div className="recall-suggestions">
              {SUGGESTIONS.map((s) => (
                <button
                  key={s}
                  type="button"
                  className="recall-suggestion"
                  onClick={() => void ask(s)}
                >
                  {s}
                </button>
              ))}
            </div>
          </div>
        ) : null}
        {turns.map((t, i) => (
          <div key={i} className={`msg ${t.role}${t.error ? ' error' : ''}`}>
            {t.role === 'assistant' && !t.error ? (
              <div className="msg-body msg-md">
                <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownLinkComponents()}>
                  {t.content}
                </ReactMarkdown>
              </div>
            ) : (
              <div className="msg-body">{t.content}</div>
            )}
            {t.degraded && !t.error ? <div className="msg-flag muted">degraded</div> : null}
            {t.citations && t.citations.length > 0 ? (
              <ul className="cite-list">
                {t.citations.map((c) => (
                  <li key={`${c.kind}-${c.id}`}>
                    <button
                      type="button"
                      onClick={() => onCitationClick(c)}
                      title={c.snippet || c.id}
                    >
                      <span className="cite-head">
                        <span className={`cite-kind kind-${c.kind}`}>{c.kind}</span>
                        <span className="cite-id">{shortNodeId(c.id)}</span>
                      </span>
                      <span className="muted">{(c.snippet || '').slice(0, 120)}</span>
                    </button>
                  </li>
                ))}
              </ul>
            ) : null}
            {t.role === 'assistant' ? (
              <div className="msg-actions">
                <button
                  type="button"
                  className="msg-action"
                  onClick={() => void copyTurn(t.content, i)}
                >
                  <IconCopy />
                  {copiedIdx === i ? 'Copied' : 'Copy'}
                </button>
                {t.error ? (
                  <button type="button" className="msg-action" onClick={() => retryFrom(i)}>
                    <IconRetry />
                    Retry
                  </button>
                ) : null}
              </div>
            ) : null}
          </div>
        ))}
        {busy ? <p className="muted pad-sm recall-thinking">Thinking…</p> : null}
      </div>

      {lastAssistant && !lastAssistant.degraded ? (
        <div className="recall-actions">
          <button
            type="button"
            className="btn"
            onClick={() =>
              onPinConcept(
                lastAssistant.content,
                seedNodes.map((n) => n.id),
              )
            }
          >
            Pin as concept
          </button>
        </div>
      ) : null}

      <form
        className="recall-compose"
        onSubmit={(e) => {
          e.preventDefault()
          void ask()
        }}
      >
        <input
          className="field"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="Ask your brain…"
          disabled={busy}
        />
        {busy ? (
          <button
            type="button"
            className="btn"
            onClick={stop}
            title="Stop generating"
            aria-label="Stop generating"
          >
            <IconStop />
          </button>
        ) : (
          <button type="submit" className="btn primary" disabled={!input.trim()}>
            Ask
          </button>
        )}
      </form>
    </div>
  )
}