import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import type { ConnectInfo, HealthResponse, ModelsState } from '../api/types'
import { E2eeSettings } from '../components/E2eeSettings'
import { PairedDevices } from '../components/PairedDevices'
import { StatusPane } from '../components/StatusPane'
import './SettingsView.css'

type ProviderId = 'ollama' | 'grok' | 'vertex'

export function SettingsView() {
  const [health, setHealth] = useState<HealthResponse | null>(null)
  const [connect, setConnect] = useState<ConnectInfo | null>(null)
  const [models, setModels] = useState<ModelsState | null>(null)
  const [draft, setDraft] = useState<Partial<ModelsState>>({})
  const [status, setStatus] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [processBusy, setProcessBusy] = useState(false)

  const [qrStamp] = useState(() => Date.now())

  const load = useCallback(async (signal?: AbortSignal) => {
    setError(null)
    setLoading(true)
    try {
      const [h, c, m] = await Promise.all([
        api.health({ signal }),
        api.connect({ signal }),
        api.models.get({ signal }),
      ])
      if (signal?.aborted) return
      setHealth(h)
      setConnect(c)
      setModels(m)
      setDraft({
        llm: m.llm,
        embed: m.embed,
        vision: m.vision,
        base_url: m.base_url,
        num_ctx: m.num_ctx,
        temperature: m.temperature,
        provider: m.provider || 'ollama',
        cloud_consent: m.cloud_consent ?? false,
        vision_cloud_consent: m.vision_cloud_consent ?? false,
        grok_base_url: m.grok_base_url,
        grok_model: m.grok_model,
        vertex_project: m.vertex_project,
        vertex_location: m.vertex_location,
        vertex_model: m.vertex_model,
      })
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') return
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      if (!signal?.aborted) setLoading(false)
    }
  }, [])

  useEffect(() => {
    const ac = new AbortController()
    void load(ac.signal)
    return () => ac.abort()
  }, [load])

  async function saveModels() {
    setStatus('')
    setError(null)
    try {
      const next = await api.models.set(draft)
      setModels(next)
      setStatus('Models saved')
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  async function runProcess() {
    setProcessBusy(true)
    setStatus('')
    try {
      await api.process({ run_brain: true, dry_run: false })
      setStatus('Process finished')
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setProcessBusy(false)
    }
  }

  const provider = (draft.provider || models?.provider || 'ollama') as ProviderId
  const options = provider === 'ollama' && models?.available?.length ? models.available : null
  const providerLabel =
    health?.provider_ok || models?.provider_ok
      ? `${models?.provider || health?.provider || 'ollama'} online`
      : `${models?.provider || health?.provider || 'ollama'} offline`

  return (
    <div className="settings">
      <section className="settings-card glass">
        <h2 className="serif">Vault & server</h2>
        <StatusPane loading={loading} error={error} onRetry={() => void load()} className="pad-sm">
          <dl className="settings-dl">
            <div>
              <dt>Vault path</dt>
              <dd>{health?.chronicle_dir || '—'}</dd>
            </div>
            <div>
              <dt>LLM provider</dt>
              <dd>{providerLabel}</dd>
            </div>
            <div>
              <dt>Ollama (embeds)</dt>
              <dd>{health?.ollama || models?.ollama_ok ? 'online' : 'offline'}</dd>
            </div>
            <div>
              <dt>Advertise</dt>
              <dd>{connect?.base || '—'}</dd>
            </div>
          </dl>
          <button
            type="button"
            className="btn"
            disabled={processBusy}
            onClick={() => void runProcess()}
          >
            {processBusy ? 'Processing…' : 'Run process'}
          </button>
          {status ? <p className="muted">{status}</p> : null}
        </StatusPane>
      </section>

      <section className="settings-card glass">
        <h2 className="serif">Phone pairing</h2>
        <p className="muted">Scan in Android → Settings → Scan Mac QR</p>
        {loading ? (
          <StatusPane loading className="pad-sm" />
        ) : connect?.base ? (
          <>
            <img
              className="pair-qr"
              src={`/connect/qr.svg?t=${qrStamp}`}
              alt="Chronicle connect QR"
            />
            <code className="pair-base">{connect.base}</code>
          </>
        ) : (
          <p className="muted">Connect info unavailable</p>
        )}
      </section>

      <section className="settings-card glass">
        <h2 className="serif">Paired phones</h2>
        <p className="muted">
          Devices allowed to sync over LAN. Revoking takes effect immediately.
        </p>
        <PairedDevices />
      </section>

      <section className="settings-card glass">
        <h2 className="serif">Encryption</h2>
        <p className="muted">
          Optional passphrase encryption for entry text (contract v1.11). Same
          passphrase on Mac and phone; entries stay sealed in the vault.
        </p>
        <E2eeSettings />
      </section>

      <section className="settings-card glass settings-models">
        <h2 className="serif">Models & provider</h2>
        <p className="muted">
          Local-first (Ollama). Optional cloud chat on Mac: Grok or Vertex — API keys stay in{' '}
          <code>~/.config/chronicle/secrets.json</code>, never in the vault.
        </p>
        {loading ? (
          <StatusPane loading className="pad-sm" />
        ) : (
          <>
            <label>
              Provider
              <select
                className="field"
                value={provider}
                onChange={(e) =>
                  setDraft((d) => ({ ...d, provider: e.target.value as ProviderId }))
                }
              >
                <option value="ollama">Ollama (local)</option>
                <option value="grok">Grok (xAI)</option>
                <option value="vertex">Vertex AI (Google)</option>
              </select>
            </label>
            {provider !== 'ollama' ? (
              <>
                <label className="settings-check">
                  <input
                    type="checkbox"
                    checked={Boolean(draft.cloud_consent)}
                    onChange={(e) =>
                      setDraft((d) => ({ ...d, cloud_consent: e.target.checked }))
                    }
                  />
                  Allow journal/KB text to leave this machine (required for cloud)
                </label>
                <label className="settings-check">
                  <input
                    type="checkbox"
                    checked={Boolean(draft.vision_cloud_consent)}
                    onChange={(e) =>
                      setDraft((d) => ({ ...d, vision_cloud_consent: e.target.checked }))
                    }
                  />
                  Allow images to cloud (separate consent)
                </label>
              </>
            ) : null}
            <label>
              Chat model
              <ModelField
                value={draft.llm || ''}
                options={options}
                onChange={(v) => setDraft((d) => ({ ...d, llm: v }))}
              />
            </label>
            <label>
              Embed (Ollama only)
              <ModelField
                value={draft.embed || ''}
                options={models?.available?.length ? models.available : null}
                onChange={(v) => setDraft((d) => ({ ...d, embed: v }))}
              />
            </label>
            <label>
              Vision model
              <ModelField
                value={draft.vision || ''}
                options={options}
                onChange={(v) => setDraft((d) => ({ ...d, vision: v }))}
              />
            </label>
            {provider === 'ollama' ? (
              <>
                <label>
                  Ollama base URL
                  <input
                    className="field"
                    value={draft.base_url || ''}
                    onChange={(e) => setDraft((d) => ({ ...d, base_url: e.target.value }))}
                  />
                </label>
                <div className="settings-row">
                  <label>
                    num_ctx
                    <input
                      className="field"
                      type="number"
                      value={draft.num_ctx ?? ''}
                      onChange={(e) =>
                        setDraft((d) => ({
                          ...d,
                          num_ctx: Number(e.target.value) || undefined,
                        }))
                      }
                    />
                  </label>
                  <label>
                    temperature
                    <input
                      className="field"
                      type="number"
                      step="0.05"
                      value={draft.temperature ?? ''}
                      onChange={(e) =>
                        setDraft((d) => ({
                          ...d,
                          temperature: e.target.value === '' ? null : Number(e.target.value),
                        }))
                      }
                    />
                  </label>
                </div>
              </>
            ) : null}
            {provider === 'grok' ? (
              <>
                <label>
                  Grok base URL
                  <input
                    className="field"
                    value={draft.grok_base_url || ''}
                    onChange={(e) =>
                      setDraft((d) => ({ ...d, grok_base_url: e.target.value }))
                    }
                  />
                </label>
                <label>
                  Grok model override
                  <input
                    className="field"
                    value={draft.grok_model || ''}
                    placeholder="defaults to chat model"
                    onChange={(e) =>
                      setDraft((d) => ({ ...d, grok_model: e.target.value || null }))
                    }
                  />
                </label>
                <p className="muted">
                  Set <code>GROK_API_KEY</code> or <code>grok_api_key</code> in secrets.json
                </p>
              </>
            ) : null}
            {provider === 'vertex' ? (
              <>
                <label>
                  GCP project
                  <input
                    className="field"
                    value={draft.vertex_project || ''}
                    onChange={(e) =>
                      setDraft((d) => ({ ...d, vertex_project: e.target.value || null }))
                    }
                  />
                </label>
                <label>
                  Location
                  <input
                    className="field"
                    value={draft.vertex_location || 'us-central1'}
                    onChange={(e) =>
                      setDraft((d) => ({ ...d, vertex_location: e.target.value }))
                    }
                  />
                </label>
                <label>
                  Vertex model override
                  <input
                    className="field"
                    value={draft.vertex_model || ''}
                    placeholder="defaults to chat model"
                    onChange={(e) =>
                      setDraft((d) => ({ ...d, vertex_model: e.target.value || null }))
                    }
                  />
                </label>
                <p className="muted">
                  Use Application Default Credentials (
                  <code>gcloud auth application-default login</code>). Optional:{' '}
                  <code>pip install google-auth</code>
                </p>
              </>
            ) : null}
            {models?.provider_error ? (
              <p className="muted">Provider: {models.provider_error}</p>
            ) : null}
            <button type="button" className="btn primary" onClick={() => void saveModels()}>
              Save models
            </button>
          </>
        )}
      </section>
    </div>
  )
}

function ModelField({
  value,
  options,
  onChange,
}: {
  value: string
  options: string[] | null
  onChange: (v: string) => void
}) {
  if (options) {
    return (
      <select className="field" value={value} onChange={(e) => onChange(e.target.value)}>
        {!options.includes(value) && value ? <option value={value}>{value}</option> : null}
        {options.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
    )
  }
  return <input className="field" value={value} onChange={(e) => onChange(e.target.value)} />
}
