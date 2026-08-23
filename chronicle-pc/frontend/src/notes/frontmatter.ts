const FM_RE = /^---\r?\n([\s\S]*?)\r?\n---/

const DEFAULT_KEY_ORDER = ['title', 'created', 'updated', 'type', 'tags', 'aliases'] as const

/** Parse simple YAML frontmatter (key: value lines). */
export function parseFrontmatter(content: string): {
  frontmatter: Record<string, string>
  body: string
} {
  const m = content.match(FM_RE)
  if (!m) return { frontmatter: {}, body: content }
  const fm: Record<string, string> = {}
  for (const line of m[1].split('\n')) {
    const idx = line.indexOf(':')
    if (idx <= 0) continue
    fm[line.slice(0, idx).trim()] = line.slice(idx + 1).trim()
  }
  return { frontmatter: fm, body: content.slice(m[0].length) }
}

function formatFrontmatter(fm: Record<string, string>): string {
  const ordered: [string, string][] = []
  const seen = new Set<string>()
  for (const key of DEFAULT_KEY_ORDER) {
    if (key in fm) {
      ordered.push([key, fm[key]])
      seen.add(key)
    }
  }
  for (const [k, v] of Object.entries(fm)) {
    if (!seen.has(k)) ordered.push([k, v])
  }
  const lines = ordered.map(([k, v]) => `${k}: ${v}`)
  return `---\n${lines.join('\n')}\n---`
}

function setKey(
  fm: Record<string, string>,
  key: string,
  value: string,
  overwrite: boolean,
): void {
  const lowerMap = new Map(Object.keys(fm).map((k) => [k.toLowerCase(), k]))
  const existing = lowerMap.get(key.toLowerCase())
  if (existing !== undefined) {
    if (!overwrite && fm[existing].trim() !== '') return
    if (existing !== key) delete fm[existing]
  }
  fm[key] = value
}

/** Set or add ``updated:`` to today's ISO date (YYYY-MM-DD). */
export function bumpUpdated(content: string, today = new Date().toISOString().slice(0, 10)): string {
  const { frontmatter, body } = parseFrontmatter(content)
  frontmatter.updated = today
  return `${formatFrontmatter(frontmatter)}${body}`
}

/**
 * Fill missing create-time frontmatter (created/updated/type/title/tags)
 * per `_system/conventions.md`. Existing keys are preserved; ``updated`` is stamped.
 */
export function ensureCreateFrontmatter(
  content: string,
  opts: { title?: string; type?: string; today?: string } = {},
): string {
  const today = opts.today ?? new Date().toISOString().slice(0, 10)
  const { frontmatter, body } = parseFrontmatter(content)
  const title = opts.title?.trim()
  if (title) setKey(frontmatter, 'title', title, false)
  setKey(frontmatter, 'created', today, false)
  setKey(frontmatter, 'updated', today, true)
  setKey(frontmatter, 'type', opts.type ?? 'note', false)
  setKey(frontmatter, 'tags', '[]', false)
  let nextBody = body
  if (nextBody && !nextBody.startsWith('\n')) nextBody = `\n${nextBody}`
  if (!nextBody) nextBody = '\n'
  return `${formatFrontmatter(frontmatter)}${nextBody}`
}
