import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { BrainView } from './BrainView'

vi.mock('../api/client', () => ({
    api: {
        brain: {
            graph: vi.fn(async () => ({
                version: 1,
                nodes: [
                    { id: 'concept:alpha', kind: 'concept', label: 'Alpha Project', group: 'ai' },
                    { id: 'project:beta', kind: 'project', label: 'Beta', group: 'ml' },
                ],
                edges: [{ from: 'concept:alpha', to: 'project:beta', rel: 'related' }],
                groups: {
                    ai: { label: 'AI', color: '#5C4F8A' },
                    ml: { label: 'ML', color: '#A67C3D' },
                },
            })),
            insights: vi.fn(async () => ({ insights: [], dates: [] })),
        },
        entries: { get: vi.fn() },
        curation: { post: vi.fn(async () => ({ ok: true, op: {} })) },
        recall: vi.fn(async () => ({
            answer: 'ok',
            citations: [],
            seed_node_ids: [],
            degraded: false,
        })),
        process: vi.fn(async () => ({})),
    },
}))

beforeAll(() => {
    class ResizeObserverStub {
        observe() { }
        unobserve() { }
        disconnect() { }
    }
    vi.stubGlobal('ResizeObserver', ResizeObserverStub)
    // jsdom does not implement Element.scrollTo
    Object.defineProperty(Element.prototype, 'scrollTo', {
        value: () => { },
        writable: true,
    })
})

afterEach(() => cleanup())

function renderBrain() {
    return render(<BrainView onOpenEntry={vi.fn()} onOpenNote={vi.fn()} />)
}

describe('BrainView', () => {
    it('renders toolbar, stats, legend, tabs, and suggested prompts once loaded', async () => {
        renderBrain()
        await waitFor(() => expect(screen.getByText('2 nodes · 1 edges')).toBeInTheDocument())

        expect(screen.getByPlaceholderText('Find node…')).toBeInTheDocument()
        expect(screen.getByRole('button', { name: 'Zoom in' })).toBeInTheDocument()
        expect(screen.getByRole('button', { name: 'Zoom out' })).toBeInTheDocument()
        expect(screen.getByRole('button', { name: 'Fit graph to view' })).toBeInTheDocument()

        expect(screen.getByTitle('Isolate AI')).toBeInTheDocument()
        expect(screen.getByRole('tab', { name: 'Recall' })).toBeInTheDocument()
        expect(screen.getByRole('tab', { name: /Inspector/ })).toBeInTheDocument()
        expect(screen.getByText('Summarize my recent ideas')).toBeInTheDocument()
    })

    it('finds a node via search, selects it, jumps to Inspector, and Esc clears', async () => {
        const user = userEvent.setup()
        renderBrain()
        await waitFor(() => expect(screen.getByText('2 nodes · 1 edges')).toBeInTheDocument())

        await user.type(screen.getByPlaceholderText('Find node…'), 'alpha')
        const option = await screen.findByRole('option', { name: /Alpha Project/ })
        await user.click(option)

        // Selection opens the Inspector tab and seeds the (hidden but mounted) chat
        await waitFor(() =>
            expect(screen.getByRole('tab', { name: /Inspector/ })).toHaveAttribute(
                'aria-selected',
                'true',
            ),
        )
        expect(screen.getByRole('heading', { name: 'Alpha Project' })).toBeInTheDocument()

        fireEvent.keyDown(document, { key: 'Escape' })
        await waitFor(() =>
            expect(
                screen.getByText('Select a node to inspect links, insights, and curation.'),
            ).toBeInTheDocument(),
        )
    })

    it('isolates a category from the legend and restores on second click', async () => {
        const user = userEvent.setup()
        const { container } = renderBrain()
        await waitFor(() => expect(screen.getByText('2 nodes · 1 edges')).toBeInTheDocument())
        await waitFor(() => expect(container.querySelectorAll('g.node').length).toBe(2))

        const aiBtn = screen.getByTitle('Isolate AI')
        await user.click(aiBtn)
        expect(aiBtn).toHaveAttribute('aria-pressed', 'true')
        await waitFor(() => expect(container.querySelectorAll('g.node').length).toBe(1))

        await user.click(aiBtn)
        expect(aiBtn).toHaveAttribute('aria-pressed', 'false')
        await waitFor(() => expect(container.querySelectorAll('g.node').length).toBe(2))
    })
})