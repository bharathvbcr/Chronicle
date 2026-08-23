import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { ApiError, api } from '../api/client'
import { onVaultChanged } from '../lib/vaultBus'
import type { KbNoteConflict, KbTreeNode, NotesSection } from '../api/types'
import { AppDialog } from '../components/AppDialog'
import { NotesSectionTabs } from '../components/NotesSectionTabs'
import { StatusPane } from '../components/StatusPane'
import { bumpUpdated, ensureCreateFrontmatter } from '../notes/frontmatter'
import { isCrossSectionPath } from '../notes/notesRouting'
import {
  collectHubRows,
  FILE_TO_AREAS,
  filterTree,
  flattenTreeFiles,
  parentFolder,
  resolveWikilinkTarget,
  seedFromTemplate,
  type HubRow,
} from '../notes/treeUtils'
import { markdownLinkComponents } from '../notes/safeMarkdownLink'
import { wikilinksToMarkdown } from '../notes/wikilinks'
import './NotesPanes.css'

interface KnowledgePaneProps {
  /** 'kb' = 30-Knowledge; 'notes' = 00-Inbox/10-Work/20-Personal/90-Archive. */
  section: NotesSection
  initialPath?: string | null
  onInitialPathConsumed?: () => void
  /** Cross-section / hub open via App (`notesRouteFor` + navigate). */
  onOpenNote?: (path: string) => void
}

const DEFAULT_AREA: Record<NotesSection, string> = {
  kb: '30-Knowledge',
  notes: '00-Inbox',
}

const SECTION_LABEL: Record<NotesSection, string> = {
  kb: 'Knowledge Base',
  notes: 'Notes',
}

const SECTION_EMPTY: Record<NotesSection, string> = {
  kb: 'No knowledge base notes yet. Create one to get started.',
  notes: 'No notes yet. Create one to get started.',
}

const SECTION_AREA_RE: Record<NotesSection, RegExp> = {
  kb: /^(30-Knowledge)\//,
  notes: /^(00-Inbox|10-Work|20-Personal|90-Archive)\//,
}

type KbTemplate = { name: string; path: string; content: string }

/** Editable note list + editor for one Notes sub-area (Knowledge Base or Notes). */
export function KnowledgePane({
  section,
  initialPath = null,
  onInitialPathConsumed,
  onOpenNote,
}: KnowledgePaneProps) {
  const navigate = useNavigate()
  const [tree, setTree] = useState<KbTreeNode | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  const [allFiles, setAllFiles] = useState<string[]>([])
  const [vaultFiles, setVaultFiles] = useState<string[]>([])
  const [path, setPath] = useState<string | null>(null)
  const [content, setContent] = useState('')
  const [contentHash, setContentHash] = useState<string | null>(null)
  const [draft, setDraft] = useState('')
  const [preview, setPreview] = useState(true)
  const [dirty, setDirty] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState('')
  const [conflict, setConflict] = useState<KbNoteConflict | null>(null)
  const [treeLoading, setTreeLoading] = useState(true)
  const [noteLoading, setNoteLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [folderContext, setFolderContext] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [templates, setTemplates] = useState<KbTemplate[]>([])
  const [createTemplate, setCreateTemplate] = useState('')
  const [moveOpen, setMoveOpen] = useState(false)
  const [fileToOpen, setFileToOpen] = useState(false)
  const [fileToArea, setFileToArea] = useState<string>(FILE_TO_AREAS[0].id)
  const [archiveOpen, setArchiveOpen] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [pendingNav, setPendingNav] = useState<string | null>(null)

  const loadTree = useCallback(async (signal?: AbortSignal) => {
    setTreeLoading(true)
    try {
      const [kb, vault] = await Promise.all([
        api.kb.tree({ section }, { signal }),
        api.kb.tree(undefined, { signal }),
      ])
      if (signal?.aborted) return
      setTree(kb.tree)
      setAllFiles(kb.files || [])
      setVaultFiles(vault.files || [])
      setError(null)
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') return
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      if (!signal?.aborted) setTreeLoading(false)
    }
  }, [section])

  // Full reset only when the section actually changes — never on background
  // vault events, which must not discard an open editor or unsaved draft.
  useEffect(() => {
    setPath(null)
    setContent('')
    setContentHash(null)
    setDraft('')
    setDirty(false)
    setError(null)
    setConflict(null)
    setFolderContext(null)
    setSearchQuery('')
    const ac = new AbortController()
    void loadTree(ac.signal)
    return () => ac.abort()
  }, [loadTree, section])
  // Live updates (v1.11): refetch when the SSE stream or an E2EE flip signals
  // a vault change. 'manual' (own edits) is excluded to avoid double-loads.
  useEffect(
    () =>
      onVaultChanged((reason) => {
        if (reason === 'sse' || reason === 'e2ee') setReloadKey((k) => k + 1)
      }),
    [],
  )
  // A background vault event refreshes the tree; an open, unmodified note is
  // quietly re-opened so external edits appear. A DIRTY editor is left
  // untouched — a routine phone capture syncing in must never wipe a draft.
  useEffect(() => {
    if (reloadKey === 0) return
    const ac = new AbortController()
    void loadTree(ac.signal).catch(() => {})
    if (path && !dirty) void openNote(path, ac.signal).catch(() => {})
    return () => ac.abort()
    // Runs only on reloadKey bumps; path/dirty are read at that moment.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reloadKey])


  const openNote = useCallback(async (notePath: string, signal?: AbortSignal) => {
    setError(null)
    setConflict(null)
    setStatus('')
    setNoteLoading(true)
    try {
      const note = await api.kb.get(notePath, { signal })
      if (signal?.aborted) return
      setPath(note.path)
      setContent(note.content)
      setContentHash(typeof note.content_hash === 'string' ? note.content_hash : null)
      setDraft(note.content)
      setDirty(false)
      setFolderContext(parentFolder(note.path))
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') return
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      if (!signal?.aborted) setNoteLoading(false)
    }
  }, [])

  useEffect(() => {
    if (!initialPath) return
    const ac = new AbortController()
    void openNote(initialPath, ac.signal)
    onInitialPathConsumed?.()
    return () => ac.abort()
  }, [initialPath, openNote, onInitialPathConsumed])

  const filePaths = useMemo(() => {
    const fromTree = flattenTreeFiles(tree)
    const merged = new Set([...vaultFiles, ...allFiles, ...fromTree, 'Home.md', 'Upcoming.md'])
    return [...merged]
  }, [allFiles, tree, vaultFiles])

  const hubRows = useMemo(() => collectHubRows(allFiles, section), [allFiles, section])

  const displayTree = useMemo(() => {
    if (!tree) return null
    return searchQuery.trim() ? filterTree(tree, searchQuery) : tree
  }, [tree, searchQuery])

  function openResolved(notePath: string) {
    if (isCrossSectionPath(notePath, section)) {
      onOpenNote?.(notePath)
      return
    }
    void openNote(notePath)
  }

  function requestOpen(notePath: string) {
    if (dirty && path && notePath !== path) {
      setPendingNav(notePath)
      return
    }
    openResolved(notePath)
  }

  function onHubClick(row: HubRow) {
    if (row.kind === 'upcoming') {
      if (onOpenNote) onOpenNote('Upcoming.md')
      else navigate('/vault/journal')
      return
    }
    requestOpen(row.path)
  }

  function onWikilinkClick(target: string) {
    const resolved = resolveWikilinkTarget(target, filePaths)
    if (resolved) requestOpen(resolved)
    else setError(`No note found for [[${target}]]`)
  }

  async function save() {
    if (!path || !dirty || saving) return
    setSaving(true)
    setError(null)
    setConflict(null)
    try {
      const toSave = bumpUpdated(draft)
      const body: {
        content: string
        base_hash?: string
      } = { content: toSave }
      if (contentHash) body.base_hash = contentHash
      const saved = await api.kb.put(path, body)
      setContent(saved.content)
      setContentHash(typeof saved.content_hash === 'string' ? saved.content_hash : null)
      setDraft(saved.content)
      setDirty(false)
      setStatus('Saved')
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        const detail = (e.body as { detail?: KbNoteConflict | string })?.detail
        if (detail && typeof detail === 'object' && 'on_disk_hash' in detail) {
          setConflict(detail)
          setError(
            `This note was edited outside the app since you loaded it.\n` +
              `on_disk: ${(detail.on_disk_hash || '').slice(0, 12)}…\n` +
              `Refresh to load the latest text — your draft stays in the editor.`,
          )
          return
        }
      }
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  async function reloadAfterConflict() {
    if (!path) return
    setConflict(null)
    await openNote(path)
  }

  async function openCreate() {
    setCreateOpen(true)
    setCreateTemplate('')
    try {
      const res = await api.kb.templates()
      setTemplates(res.files || [])
      if (res.files?.length) setCreateTemplate(res.files[0].name)
    } catch {
      setTemplates([])
    }
  }

  async function createNote(name: string | undefined) {
    setCreateOpen(false)
    if (!name?.trim()) return
    const rel = name.replace(/^\//, '').trim()
    if (!rel) return
    const full = rel.endsWith('.md') ? rel : `${rel}.md`
    const isExplicitArea = SECTION_AREA_RE[section].test(full)
    if (/^(00-Inbox|10-Work|20-Personal|30-Knowledge|90-Archive)\//.test(full) && !isExplicitArea) {
      setError(`Path must stay under ${SECTION_LABEL[section]} (default ${DEFAULT_AREA[section]}/)`)
      return
    }
    let createPath = isExplicitArea ? full : `${DEFAULT_AREA[section]}/${full}`
    if (!isExplicitArea && folderContext && SECTION_AREA_RE[section].test(`${folderContext}/`)) {
      createPath = `${folderContext}/${full}`
    }
    const title = full.replace(/\.md$/, '').split('/').pop() || full
    const tpl = templates.find((t) => t.name === createTemplate)
    const raw = tpl
      ? seedFromTemplate(tpl.content, title)
      : `# ${title}\n\n`
    const body = ensureCreateFrontmatter(raw, { title })
    try {
      await api.kb.create(createPath, { content: body, title, section })
      await loadTree()
      await openNote(createPath)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  function clearNoteSelection() {
    setPath(null)
    setContent('')
    setContentHash(null)
    setDraft('')
    setDirty(false)
    setStatus('')
    setConflict(null)
  }

  async function archiveNote() {
    if (!path) return
    setArchiveOpen(false)
    setError(null)
    try {
      await api.kb.archive({ path })
      clearNoteSelection()
      await loadTree()
      setStatus('Archived')
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  async function moveNote(toPath: string | undefined) {
    setMoveOpen(false)
    if (!path || !toPath?.trim()) return
    let dest = toPath.trim().replace(/^\//, '')
    if (!dest.endsWith('.md')) dest = `${dest}.md`
    setError(null)
    try {
      const res = await api.kb.move({ from_path: path, to_path: dest })
      await loadTree()
      await openNote(res.to_path)
      setStatus('Moved')
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  async function fileToNote(subfolder: string | undefined) {
    setFileToOpen(false)
    if (!path) return
    const area = FILE_TO_AREAS.find((a) => a.id === fileToArea)?.prefix
    if (!area) return
    const sub = (subfolder || '').trim().replace(/^\/+|\/+$/g, '')
    const base = path.split('/').pop() || path
    const dest = sub ? `${area}/${sub}/${base}` : `${area}/${base}`
    setError(null)
    try {
      const res = await api.kb.move({ from_path: path, to_path: dest })
      await loadTree()
      await openNote(res.to_path)
      setStatus('Filed')
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  async function deleteNote() {
    setDeleteOpen(false)
    if (!path) return
    setError(null)
    try {
      await api.kb.remove(path)
      clearNoteSelection()
      await loadTree()
      setStatus('Deleted')
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  const treeEmpty = !treeLoading && (!displayTree || !(displayTree.children && displayTree.children.length))

  const previewMd = wikilinksToMarkdown(draft || content)

  return (
    <div className="notes">
      <aside className="notes-nav glass">
        <div className="notes-nav-head">
          <NotesSectionTabs dirty={dirty} />
        </div>
        <div className="notes-nav-head">
          <span className="muted" style={{ fontSize: '0.8rem' }}>
            {SECTION_LABEL[section]}
          </span>
          <div className="spacer" />
          <button type="button" className="btn" onClick={() => void openCreate()}>
            New
          </button>
        </div>
        <div className="notes-nav-head notes-search-row">
          <input
            className="field notes-search"
            type="search"
            placeholder="Filter notes…"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            aria-label="Filter notes"
          />
        </div>
        <div className="notes-hubs">
          {hubRows.map((row) => (
            <button
              key={row.path}
              type="button"
              className={`tree-file hub-row${row.kind === 'upcoming' ? ' hub-journal' : ''}${path === row.path ? ' active' : ''}`}
              onClick={() => onHubClick(row)}
            >
              {row.label}
              {row.kind === 'upcoming' ? <span className="muted hub-badge">Journal</span> : null}
            </button>
          ))}
        </div>
        <StatusPane
          loading={treeLoading}
          error={error && !path ? error : null}
          empty={treeEmpty && !searchQuery.trim()}
          emptyMessage={searchQuery.trim() ? 'No notes match your filter.' : SECTION_EMPTY[section]}
          onRetry={() => void loadTree()}
          className="pad"
        >
          {displayTree ? (
            <TreeList
              node={displayTree}
              active={path}
              folderContext={folderContext}
              onOpen={requestOpen}
              onSelectFolder={setFolderContext}
            />
          ) : null}
        </StatusPane>
      </aside>
      <section className="notes-editor glass">
        {noteLoading ? (
          <StatusPane loading className="pad" />
        ) : !path ? (
          <StatusPane empty emptyMessage={`Select a note, or create one in ${SECTION_LABEL[section]}.`} className="pad" />
        ) : (
          <>
            <header className="notes-toolbar">
              <h2 className="serif">{path}</h2>
              <div className="spacer" />
              {status ? <span className="muted">{status}</span> : null}
              <button type="button" className="btn" onClick={() => setFileToOpen(true)}>
                File to…
              </button>
              <button type="button" className="btn" onClick={() => setMoveOpen(true)}>
                Move
              </button>
              <button type="button" className="btn primary" onClick={() => setArchiveOpen(true)}>
                Archive
              </button>
              <button type="button" className="btn danger-muted" onClick={() => setDeleteOpen(true)}>
                Delete
              </button>
              <button
                type="button"
                className="btn"
                aria-pressed={preview}
                onClick={() => setPreview((p) => !p)}
              >
                {preview ? 'Edit' : 'Preview'}
              </button>
              <button type="button" className="btn primary" disabled={!dirty || saving} onClick={() => void save()}>
                Save
              </button>
            </header>
            {error ? (
              <p style={{ color: 'var(--danger)', padding: '0 1rem', whiteSpace: 'pre-wrap' }} role="alert">
                {error}
              </p>
            ) : null}
            {conflict ? (
              <p style={{ padding: '0 1rem' }}>
                <button type="button" className="btn" onClick={() => void reloadAfterConflict()}>
                  Refresh note
                </button>
              </p>
            ) : null}
            {preview ? (
              <div className="md-preview">
                <ReactMarkdown
                  remarkPlugins={[remarkGfm]}
                  components={markdownLinkComponents(onWikilinkClick)}
                >
                  {previewMd}
                </ReactMarkdown>
              </div>
            ) : (
              <textarea
                className="md-editor field"
                value={draft}
                onChange={(e) => {
                  setDraft(e.target.value)
                  setDirty(e.target.value !== content)
                  setStatus('')
                }}
              />
            )}
          </>
        )}
      </section>

      {createOpen ? (
        <CreateNoteDialog
          section={section}
          templates={templates}
          templateName={createTemplate}
          folderContext={folderContext}
          onTemplateChange={setCreateTemplate}
          onCancel={() => setCreateOpen(false)}
          onConfirm={(v) => void createNote(v)}
        />
      ) : null}

      <AppDialog
        open={moveOpen}
        title="Move note"
        message={`Destination PARA path for “${path || ''}” (e.g. 10-Work/projects/foo.md)`}
        confirmLabel="Move"
        prompt={{ placeholder: '10-Work/foo.md', defaultValue: path ? parentFolder(path) + '/' : '' }}
        onCancel={() => setMoveOpen(false)}
        onConfirm={(v) => void moveNote(v)}
      />
      {fileToOpen ? (
        <div
          className="overlay open"
          role="presentation"
          onClick={(e) => {
            if (e.target === e.currentTarget) setFileToOpen(false)
          }}
        >
          <div className="overlay-panel glass app-dialog" role="dialog" aria-modal="true">
            <h2 className="serif" style={{ margin: '0 0 0.5rem', fontSize: '1.1rem' }}>
              File to…
            </h2>
            <p className="muted" style={{ marginTop: 0 }}>
              Move “{path}” into a PARA area.
            </p>
            <div className="file-to-sheet" role="group" aria-label="Destination area">
              {FILE_TO_AREAS.map((a) => (
                <button
                  key={a.id}
                  type="button"
                  className={`btn${fileToArea === a.id ? ' active' : ''}`}
                  onClick={() => setFileToArea(a.id)}
                >
                  {a.label}
                </button>
              ))}
            </div>
            <label className="app-dialog-field">
              <span className="muted">Subfolder (optional)</span>
              <input
                className="field"
                id="file-to-sub"
                placeholder="projects/acme"
                aria-label="Subfolder"
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault()
                    void fileToNote((e.target as HTMLInputElement).value)
                  }
                }}
              />
            </label>
            <div className="app-dialog-actions">
              <button type="button" className="btn" onClick={() => setFileToOpen(false)}>
                Cancel
              </button>
              <button
                type="button"
                className="btn primary"
                onClick={() => {
                  const el = document.getElementById('file-to-sub') as HTMLInputElement | null
                  void fileToNote(el?.value)
                }}
              >
                File
              </button>
            </div>
          </div>
        </div>
      ) : null}
      <AppDialog
        open={archiveOpen}
        title="Archive note?"
        message={`Move “${path || ''}” to 90-Archive/ (preferred over delete).`}
        confirmLabel="Archive"
        onCancel={() => setArchiveOpen(false)}
        onConfirm={() => void archiveNote()}
      />
      <AppDialog
        open={deleteOpen}
        title="Hard delete?"
        message="Permanently remove this note from the vault? Prefer Archive when unsure."
        confirmLabel="Delete"
        danger
        onCancel={() => setDeleteOpen(false)}
        onConfirm={() => void deleteNote()}
      />
      <AppDialog
        open={Boolean(pendingNav)}
        title="Discard changes?"
        message="You have unsaved edits. Leave this note without saving?"
        confirmLabel="Discard"
        danger
        onCancel={() => setPendingNav(null)}
        onConfirm={() => {
          const next = pendingNav
          setPendingNav(null)
          if (next) openResolved(next)
        }}
      />
    </div>
  )
}

function CreateNoteDialog({
  section,
  templates,
  templateName,
  folderContext,
  onTemplateChange,
  onCancel,
  onConfirm,
}: {
  section: NotesSection
  templates: KbTemplate[]
  templateName: string
  folderContext: string | null
  onTemplateChange: (name: string) => void
  onCancel: () => void
  onConfirm: (name?: string) => void
}) {
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    requestAnimationFrame(() => inputRef.current?.focus())
  }, [])

  const defaultPlaceholder = folderContext
    ? `${folderContext.replace(/^.*\//, '')}/note.md`
    : 'foo.md'

  return (
    <div
      className="overlay open"
      role="presentation"
      onClick={(e) => {
        if (e.target === e.currentTarget) onCancel()
      }}
    >
      <div className="overlay-panel glass app-dialog" role="dialog" aria-modal="true">
        <h2 className="serif" style={{ margin: '0 0 0.5rem', fontSize: '1.1rem' }}>
          New {SECTION_LABEL[section].toLowerCase()} note
        </h2>
        <p className="muted" style={{ marginTop: 0 }}>
          {folderContext
            ? `Creates under ${folderContext}/ when no area prefix is given.`
            : `Path under ${DEFAULT_AREA[section]}/ (e.g. skills/foo.md)`}
        </p>
        {templates.length ? (
          <label className="app-dialog-field">
            <span className="muted">Template</span>
            <select
              className="field"
              value={templateName}
              onChange={(e) => onTemplateChange(e.target.value)}
            >
              <option value="">Blank</option>
              {templates.map((t) => (
                <option key={t.path} value={t.name}>
                  {t.name}
                </option>
              ))}
            </select>
          </label>
        ) : null}
        <label className="app-dialog-field">
          <span className="muted">Filename or path</span>
          <input
            ref={inputRef}
            className="field"
            placeholder={defaultPlaceholder}
            aria-label="Note path"
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                onConfirm(inputRef.current?.value)
              }
              if (e.key === 'Escape') {
                e.preventDefault()
                onCancel()
              }
            }}
          />
        </label>
        <div className="app-dialog-actions">
          <button type="button" className="btn" onClick={onCancel}>
            Cancel
          </button>
          <button
            type="button"
            className="btn primary"
            onClick={() => onConfirm(inputRef.current?.value)}
          >
            Create
          </button>
        </div>
      </div>
    </div>
  )
}

function TreeList({
  node,
  active,
  folderContext,
  onOpen,
  onSelectFolder,
  depth = 0,
}: {
  node: KbTreeNode
  active: string | null
  folderContext: string | null
  onOpen: (path: string) => void
  onSelectFolder: (path: string) => void
  depth?: number
}) {
  if (node.type === 'file') {
    const base = node.name || node.path.split('/').pop() || ''
    if (/^MOC-/i.test(base)) return null
    return (
      <button
        type="button"
        className={`tree-file${active === node.path ? ' active' : ''}`}
        style={{ paddingLeft: `${0.75 + depth * 0.75}rem` }}
        onClick={() => onOpen(node.path)}
      >
        {base}
      </button>
    )
  }
  const showLabel = node.path !== 'knowledge'
  const childDepth = showLabel ? depth + 1 : depth
  return (
    <div className="tree-dir">
      {showLabel ? (
        <button
          type="button"
          className={`tree-dir-label tree-dir-btn${folderContext === node.path ? ' active' : ''}`}
          style={{ paddingLeft: `${0.75 + depth * 0.75}rem` }}
          onClick={() => onSelectFolder(node.path)}
          title="New notes will be created here"
        >
          {node.path.split('/').pop()}
        </button>
      ) : null}
      {(node.children || []).map((child) => (
        <TreeList
          key={child.path}
          node={child}
          active={active}
          folderContext={folderContext}
          onOpen={onOpen}
          onSelectFolder={onSelectFolder}
          depth={childDepth}
        />
      ))}
    </div>
  )
}
