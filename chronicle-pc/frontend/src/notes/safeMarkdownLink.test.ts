import { describe, expect, it } from 'vitest'
import { isAllowedMarkdownHref } from './safeMarkdownLink'

describe('isAllowedMarkdownHref', () => {
  it('allows http https mailto and anchors', () => {
    expect(isAllowedMarkdownHref('https://example.com')).toBe(true)
    expect(isAllowedMarkdownHref('http://example.com')).toBe(true)
    expect(isAllowedMarkdownHref('mailto:a@b.c')).toBe(true)
    expect(isAllowedMarkdownHref('#section')).toBe(true)
  })

  it('allows wikilink scheme', () => {
    expect(isAllowedMarkdownHref('wikilink:Foo')).toBe(true)
  })

  it('rejects javascript and other schemes', () => {
    expect(isAllowedMarkdownHref('javascript:alert(1)')).toBe(false)
    expect(isAllowedMarkdownHref('data:text/html,hi')).toBe(false)
    expect(isAllowedMarkdownHref('file:///etc/passwd')).toBe(false)
    expect(isAllowedMarkdownHref(undefined)).toBe(false)
  })
})
