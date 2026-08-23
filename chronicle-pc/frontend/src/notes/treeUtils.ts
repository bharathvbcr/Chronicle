import type { KbTreeNode, NotesSection } from '../api/types'

export type HubRow = {
  path: string
  label: string
  kind: 'home' | 'moc' | 'upcoming'
}

/** Hub rows pinned above the file tree (Home, section MOCs, Upcoming link). */
export function collectHubRows(files: string[], section: NotesSection): HubRow[] {
  const rows: HubRow[] = [{ path: 'Home.md', label: 'Home', kind: 'home' }]
  const areaRe = section === 'kb' ? /^30-Knowledge\// : /^(00-Inbox|10-Work|20-Personal|90-Archive)\//
  for (const p of files) {
    const base = p.split('/').pop() || p
    if (!/^MOC-[^/]+\.md$/i.test(base)) continue
    if (!areaRe.test(p)) continue
    rows.push({ path: p, label: base.replace(/\.md$/i, ''), kind: 'moc' })
  }
  rows.push({ path: '__upcoming__', label: 'Upcoming', kind: 'upcoming' })
  return rows
}

/** Collect every file path from a nested kb tree. */
export function flattenTreeFiles(node: KbTreeNode | null): string[] {
  if (!node) return []
  if (node.type === 'file') return [node.path]
  return (node.children || []).flatMap((c) => flattenTreeFiles(c))
}

/** Parent PARA folder for a note path (empty string when at area root). */
export function parentFolder(notePath: string): string {
  const parts = notePath.split('/')
  parts.pop()
  return parts.join('/')
}

/** Filter tree nodes by case-insensitive substring match on path/name. */
export function filterTree(node: KbTreeNode, query: string): KbTreeNode | null {
  const q = query.trim().toLowerCase()
  if (!q) return node
  if (node.type === 'file') {
    const hay = `${node.path} ${node.name || ''}`.toLowerCase()
    return hay.includes(q) ? node : null
  }
  const children = (node.children || [])
    .map((c) => filterTree(c, query))
    .filter((c): c is KbTreeNode => c != null)
  if (!children.length) return null
  return { ...node, children }
}

/** Resolve ``[[wikilink]]`` target to a vault file path (fuzzy basename fallback). */
export function resolveWikilinkTarget(target: string, allPaths: string[]): string | null {
  const raw = target.trim().replace(/^\[\[|\]\]$/g, '')
  const pipe = raw.split('|').pop()?.trim() || raw
  const noExt = pipe.replace(/\.md$/i, '')
  const candidates = allPaths.filter((p) => {
    if (p === pipe || p === `${pipe}.md`) return true
    if (p.endsWith(`/${pipe}`) || p.endsWith(`/${noExt}.md`)) return true
    return false
  })
  if (candidates.length === 1) return candidates[0]
  if (candidates.length > 1) return candidates[0]
  const base = (noExt.split('/').pop() || noExt).toLowerCase()
  const fuzzy = allPaths.filter((p) => {
    const name = (p.split('/').pop() || '').replace(/\.md$/i, '').toLowerCase()
    return name === base
  })
  return fuzzy.length ? fuzzy[0] : null
}

/** Apply ``{{title}}`` / ``{{date}}`` placeholders from a vault template. */
export function seedFromTemplate(template: string, title: string, date = new Date().toISOString().slice(0, 10)): string {
  return template.replace(/\{\{title\}\}/g, title).replace(/\{\{date\}\}/g, date)
}

export const FILE_TO_AREAS = [
  { id: 'work', label: 'Work', prefix: '10-Work' },
  { id: 'personal', label: 'Personal', prefix: '20-Personal' },
  { id: 'knowledge', label: 'Knowledge', prefix: '30-Knowledge' },
  { id: 'archive', label: 'Archive', prefix: '90-Archive' },
] as const
