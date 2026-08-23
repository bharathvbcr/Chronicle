import type { Components } from 'react-markdown'
import { isWikilinkHref, wikilinkTargetFromHref } from './wikilinks'

/** Allow only http(s), mailto, and Chronicle wikilink buttons — strip javascript: etc. */
export function isAllowedMarkdownHref(href: string | undefined): boolean {
  if (!href) return false
  if (isWikilinkHref(href)) return true
  const trimmed = href.trim()
  const lower = trimmed.toLowerCase()
  if (lower.startsWith('http://') || lower.startsWith('https://') || lower.startsWith('mailto:')) {
    return true
  }
  // In-page anchors only (no scheme).
  if (trimmed.startsWith('#')) return true
  return false
}

/** Shared ReactMarkdown `components` for KnowledgePane + JournalPane. */
export function markdownLinkComponents(
  onWikilinkClick?: (target: string) => void,
): Components {
  return {
    a: ({ href, children }) => {
      if (isWikilinkHref(href) && onWikilinkClick) {
        return (
          <button
            type="button"
            className="wikilink"
            onClick={() => onWikilinkClick(wikilinkTargetFromHref(href!))}
          >
            {children}
          </button>
        )
      }
      if (isWikilinkHref(href)) {
        return <span className="wikilink">{children}</span>
      }
      if (!isAllowedMarkdownHref(href)) {
        return <span>{children}</span>
      }
      const external = href!.toLowerCase().startsWith('http')
      return (
        <a href={href} {...(external ? { target: '_blank', rel: 'noreferrer' } : {})}>
          {children}
        </a>
      )
    },
  }
}
