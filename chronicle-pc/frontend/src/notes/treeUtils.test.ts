import { describe, expect, it } from 'vitest'
import type { KbTreeNode } from '../api/types'
import {
  collectHubRows,
  filterTree,
  flattenTreeFiles,
  parentFolder,
  resolveWikilinkTarget,
  seedFromTemplate,
} from './treeUtils'
import { isWikilinkHref, wikilinkTargetFromHref, wikilinksToMarkdown } from './wikilinks'

const sampleTree: KbTreeNode = {
  path: '30-Knowledge',
  type: 'dir',
  children: [
    { path: '30-Knowledge/MOC-Knowledge.md', name: 'MOC-Knowledge.md', type: 'file' },
    { path: '30-Knowledge/foo.md', name: 'foo.md', type: 'file' },
  ],
}

describe('treeUtils', () => {
  it('collectHubRows includes Home, MOCs, Upcoming', () => {
    const rows = collectHubRows(['30-Knowledge/MOC-Knowledge.md', '10-Work/MOC-Work.md'], 'kb')
    expect(rows[0].kind).toBe('home')
    expect(rows.some((r) => r.kind === 'moc' && r.path.includes('MOC-Knowledge'))).toBe(true)
    expect(rows.some((r) => r.kind === 'upcoming')).toBe(true)
    expect(rows.some((r) => r.path.includes('MOC-Work'))).toBe(false)
  })

  it('flattenTreeFiles walks nested tree', () => {
    expect(flattenTreeFiles(sampleTree)).toEqual([
      '30-Knowledge/MOC-Knowledge.md',
      '30-Knowledge/foo.md',
    ])
  })

  it('parentFolder returns area path', () => {
    expect(parentFolder('10-Work/proj/note.md')).toBe('10-Work/proj')
  })

  it('filterTree matches path substring', () => {
    const filtered = filterTree(sampleTree, 'moc')
    expect(filtered?.children?.length).toBe(1)
    expect(filtered?.children?.[0].path).toContain('MOC')
  })

  it('resolveWikilinkTarget fuzzy-matches basename', () => {
    const paths = ['30-Knowledge/foo.md', '10-Work/bar.md']
    expect(resolveWikilinkTarget('foo', paths)).toBe('30-Knowledge/foo.md')
    expect(resolveWikilinkTarget('10-Work/bar', paths)).toBe('10-Work/bar.md')
  })

  it('seedFromTemplate substitutes placeholders', () => {
    const out = seedFromTemplate('# {{title}}\n{{date}}', 'My Note', '2026-07-12')
    expect(out).toBe('# My Note\n2026-07-12')
  })
})

describe('wikilinks', () => {
  it('wikilinksToMarkdown converts bracket links', () => {
    const md = wikilinksToMarkdown('See [[foo]] and [[bar|Label]]')
    expect(md).toContain('[foo](wikilink:foo)')
    expect(md).toContain('[Label](wikilink:bar%7CLabel)')
  })

  it('isWikilinkHref detects scheme', () => {
    expect(isWikilinkHref('wikilink:foo')).toBe(true)
    expect(isWikilinkHref('https://x')).toBe(false)
  })

  it('wikilinkTargetFromHref decodes target', () => {
    expect(wikilinkTargetFromHref('wikilink:path%2FNote')).toBe('path/Note')
  })
})
