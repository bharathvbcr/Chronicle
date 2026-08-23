import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { KnowledgePane } from './KnowledgePane'
import { notifyVaultChanged, resetVaultBus } from '../lib/vaultBus'

const kbGet = vi.fn()
const kbPut = vi.fn()
const kbTree = vi.fn()

vi.mock('../api/client', () => ({
  ApiError: class ApiError extends Error {
    status: number
    body: unknown
    constructor(status: number, message: string, body?: unknown) {
      super(message)
      this.status = status
      this.body = body
    }
  },
  api: {
    kb: {
      tree: (...a: unknown[]) => kbTree(...a),
      get: (...a: unknown[]) => kbGet(...a),
      put: (...a: unknown[]) => kbPut(...a),
      templates: vi.fn().mockResolvedValue({ files: [] }),
      create: vi.fn(),
      remove: vi.fn(),
      move: vi.fn(),
      archive: vi.fn(),
    },
  },
}))

vi.mock('../notes/safeMarkdownLink', () => ({
  markdownLinkComponents: () => ({}),
}))
vi.mock('../notes/wikilinks', () => ({ wikilinksToMarkdown: (s: string) => s }))

function renderPane() {
  return render(
    <MemoryRouter>
      <KnowledgePane section="kb" />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  resetVaultBus()
  kbTree.mockResolvedValue({
    tree: {
      path: '30-Knowledge',
      type: 'dir',
      children: [
        { path: '30-Knowledge/Note A.md', type: 'file', name: 'Note A.md' },
        { path: '30-Knowledge/Note B.md', type: 'file', name: 'Note B.md' },
      ],
    },
    files: ['30-Knowledge/Note A.md', '30-Knowledge/Note B.md'],
  })
  kbGet.mockImplementation(async (path: string) => ({
    path,
    content: `original ${path}`,
    content_hash: 'hash-1',
  }))
  kbPut.mockImplementation(async (path: string, body: { content: string }) => ({
    path,
    content: body.content,
    content_hash: 'hash-2',
  }))
})

afterEach(() => {
  cleanup()
  resetVaultBus()
  vi.clearAllMocks()
})

async function openFirstNote(user: ReturnType<typeof userEvent.setup>) {
  await screen.findAllByText('Knowledge Base')
  const fileBtn = await screen.findByRole('button', { name: /Note A\.md/ })
  await user.click(fileBtn)
  // Pane opens in preview mode; switch to the editor.
  const editBtn = await screen.findByRole('button', { name: /Edit/ })
  await user.click(editBtn)
  const editor = await screen.findByRole('textbox')
  return editor
}

describe('KnowledgePane background vault events', () => {
  it('keeps the dirty draft and open note when an SSE vault event arrives', async () => {
    const user = userEvent.setup()
    renderPane()
    const editor = await openFirstNote(user)

    await user.type(editor, ' plus my in-progress edit')
    expect((editor as HTMLTextAreaElement).value).toContain('in-progress edit')

    // Phone capture syncs in → server fingerprint changes.
    await actAsync(() => notifyVaultChanged('sse'))

    // Draft must survive; editor must stay open on the same note.
    expect(screen.getByDisplayValue(/in-progress edit/)).toBeTruthy()
    expect(kbPut).not.toHaveBeenCalled()
  })

  it('refreshes a clean open note on SSE events without closing it', async () => {
    const user = userEvent.setup()
    renderPane()
    await openFirstNote(user)

    kbGet.mockImplementation(async (path: string) => ({
      path,
      content: `updated ${path}`,
      content_hash: 'hash-2',
    }))

    await actAsync(() => notifyVaultChanged('sse'))

    await waitFor(() => {
      expect(
        screen.getByDisplayValue(/updated 30-Knowledge\/Note A\.md/),
      ).toBeTruthy()
    })
  })
})

describe('KnowledgePane save guard', () => {
  it('ignores double-click Save — exactly one PUT per change set', async () => {
    const user = userEvent.setup()
    renderPane()
    const editor = await openFirstNote(user)

    let resolvePut: (v: unknown) => void = () => {}
    kbPut.mockReturnValue(new Promise((res) => { resolvePut = res }))

    await user.type(editor, 'x')
    // dirty flips true after typing; fire two clicks while the first PUT is
    // still in flight.
    const saveBtn = screen.getByRole('button', { name: 'Save' })
    await user.click(saveBtn)
    await user.click(saveBtn)

    resolvePut({ path: 'p', content: 'c', content_hash: 'h2' })
    await waitFor(() => expect(kbPut).toHaveBeenCalledTimes(1))
  })

  it('does not show a conflict banner when Save is clicked once', async () => {
    const user = userEvent.setup()
    renderPane()
    const editor = await openFirstNote(user)
    await user.type(editor, 'hello')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.getByText('Saved')).toBeTruthy())
    expect(screen.queryByText(/edited outside the app/)).toBeNull()
  })
})

// Flush microtasks + state updates after firing bus notifications.
async function actAsync(fn: () => void) {
  await import('react').then(async (React) => {
    const { act } = await import('@testing-library/react')
    await act(async () => {
      fn()
      await Promise.resolve()
      React.version // keep import used
    })
  })
}
