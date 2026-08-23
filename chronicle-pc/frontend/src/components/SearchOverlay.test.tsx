import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup } from '@testing-library/react'
import { SearchOverlay } from './SearchOverlay'

const search = vi.fn()

vi.mock('../api/client', () => ({
  api: {
    search: (...a: unknown[]) => search(...a),
    recent: { list: vi.fn().mockResolvedValue({ queries: [] }) },
  },
}))

vi.mock('../hooks/useFocusTrap', () => ({ useFocusTrap: () => {} }))

function renderOpen() {
  return render(<SearchOverlay open onClose={() => {}} onActivate={() => {}} />)
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('SearchOverlay error surfacing', () => {
  it('shows "Search failed" instead of "No matches" when the API errors', async () => {
    const user = userEvent.setup()
    search.mockRejectedValue(new Error('connect ECONNREFUSED'))
    renderOpen()

    const input = screen.getByPlaceholderText(/search/i)
    await user.type(input, 'garden')

    await waitFor(() => expect(search).toHaveBeenCalled())
    await waitFor(() => {
      expect(screen.getByText(/Search failed/)).toBeTruthy()
      expect(screen.getByText(/ECONNREFUSED/)).toBeTruthy()
    })
    expect(screen.queryByText('No matches')).toBeNull()
  })

  it('shows "No matches" when the API succeeds with zero hits', async () => {
    const user = userEvent.setup()
    search.mockResolvedValue({ query: 'zzz', hits: [], ollama: false })
    renderOpen()

    const input = screen.getByPlaceholderText(/search/i)
    await user.type(input, 'zzz')

    await waitFor(() => expect(screen.getByText('No matches')).toBeTruthy())
    expect(screen.queryByText(/Search failed/)).toBeNull()
  })
})
