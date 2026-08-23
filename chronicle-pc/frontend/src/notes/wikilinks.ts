const WIKILINK_RE = /\[\[([^\]]+)\]\]/g
const WIKILINK_SCHEME = 'wikilink:'

/** Turn Obsidian ``[[links]]`` into markdown links for ReactMarkdown. */
export function wikilinksToMarkdown(content: string): string {
  return content.replace(WIKILINK_RE, (_, target: string) => {
    const raw = target.trim()
    const label = (raw.split('|').pop() || raw).trim()
    return `[${label}](${WIKILINK_SCHEME}${encodeURIComponent(raw)})`
  })
}

export function isWikilinkHref(href: string | undefined): boolean {
  return typeof href === 'string' && href.startsWith(WIKILINK_SCHEME)
}

export function wikilinkTargetFromHref(href: string): string {
  return decodeURIComponent(href.slice(WIKILINK_SCHEME.length))
}
