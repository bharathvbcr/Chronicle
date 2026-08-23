import { describe, expect, it } from 'vitest'
import { bumpUpdated, ensureCreateFrontmatter, parseFrontmatter } from './frontmatter'

describe('frontmatter', () => {
  it('parses simple YAML block', () => {
    const { frontmatter, body } = parseFrontmatter('---\ntitle: Foo\n---\n\n# Hi\n')
    expect(frontmatter.title).toBe('Foo')
    expect(body).toBe('\n\n# Hi\n')
  })

  it('bumpUpdated sets updated date', () => {
    const out = bumpUpdated('# No frontmatter\n', '2026-07-12')
    expect(out).toContain('updated: 2026-07-12')
    expect(out).toContain('# No frontmatter')
  })

  it('bumpUpdated replaces existing updated', () => {
    const src = '---\nupdated: 2020-01-01\ntitle: X\n---\n\nbody'
    const out = bumpUpdated(src, '2026-07-12')
    expect(out).toContain('updated: 2026-07-12')
    expect(out).not.toContain('2020-01-01')
    expect(out).toContain('body')
  })

  it('ensureCreateFrontmatter fills convention defaults', () => {
    const out = ensureCreateFrontmatter('# Hello\n', { title: 'Hello', today: '2026-07-12' })
    expect(out).toContain('title: Hello')
    expect(out).toContain('created: 2026-07-12')
    expect(out).toContain('updated: 2026-07-12')
    expect(out).toContain('type: note')
    expect(out).toContain('tags: []')
    expect(out).toContain('# Hello')
  })

  it('ensureCreateFrontmatter preserves existing type/created', () => {
    const src = '---\ntitle: Keep\ntype: project\ncreated: 2020-01-01\n---\n\nbody'
    const out = ensureCreateFrontmatter(src, { title: 'Ignored', today: '2026-07-12' })
    expect(out).toContain('title: Keep')
    expect(out).toContain('type: project')
    expect(out).toContain('created: 2020-01-01')
    expect(out).toContain('updated: 2026-07-12')
  })
})
