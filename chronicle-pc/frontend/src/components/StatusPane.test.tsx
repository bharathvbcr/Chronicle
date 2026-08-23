import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { StatusPane } from '../components/StatusPane'
import { SearchOverlay } from '../components/SearchOverlay'

describe('StatusPane', () => {
  it('shows loading, empty, and error states', () => {
    const { rerender } = render(<StatusPane loading loadingMessage="Wait…" />)
    expect(screen.getByRole('status')).toHaveTextContent('Wait…')

    rerender(<StatusPane empty emptyMessage="Nothing" />)
    expect(screen.getByText('Nothing')).toBeInTheDocument()

    rerender(<StatusPane error="boom" />)
    expect(screen.getByRole('alert')).toHaveTextContent('boom')
  })

  it('renders retry and empty actions', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    const onEmpty = vi.fn()
    const { rerender } = render(<StatusPane error="boom" onRetry={onRetry} />)
    await user.click(screen.getByRole('button', { name: 'Retry' }))
    expect(onRetry).toHaveBeenCalledTimes(1)

    rerender(
      <StatusPane empty emptyMessage="Nothing" emptyAction={{ label: 'Add entry', onClick: onEmpty }} />,
    )
    await user.click(screen.getByRole('button', { name: 'Add entry' }))
    expect(onEmpty).toHaveBeenCalledTimes(1)
  })

  it('renders children when idle', () => {
    render(
      <StatusPane>
        <span>content</span>
      </StatusPane>,
    )
    expect(screen.getByText('content')).toBeInTheDocument()
  })
})

describe('SearchOverlay j/k typing', () => {
  it('allows typing j and k in the query input', async () => {
    const user = userEvent.setup()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({ query: 'jk', hits: [], ollama: false }),
      }),
    )

    render(<SearchOverlay open onClose={() => {}} onActivate={() => {}} />)
    const input = screen.getByLabelText('Search query')
    await user.type(input, 'jk')
    expect(input).toHaveValue('jk')
  })
})
