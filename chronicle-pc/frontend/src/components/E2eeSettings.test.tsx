import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { E2eeSettings } from './E2eeSettings'
import { onVaultChanged, resetVaultBus } from '../lib/vaultBus'

vi.mock('../api/client', () => ({
  api: {
    e2ee: {
      status: vi.fn(),
      unlock: vi.fn(),
      lock: vi.fn(),
    },
  },
}))

import { api } from '../api/client'

const statusMock = vi.mocked(api.e2ee.status)
const unlockMock = vi.mocked(api.e2ee.unlock)
const lockMock = vi.mocked(api.e2ee.lock)

beforeEach(() => {
  vi.clearAllMocks()
  resetVaultBus()
})

afterEach(() => cleanup())

describe('E2eeSettings', () => {
  it('explains how to enable when e2ee is off', async () => {
    statusMock.mockResolvedValue({ enabled: false })
    render(<E2eeSettings />)
    await waitFor(() =>
      expect(screen.getByText(/chronicle e2ee-setup/)).toBeInTheDocument(),
    )
  })

  it('shows the passphrase form while locked and unlocks via the API', async () => {
    const user = userEvent.setup()
    const seen: string[] = []
    onVaultChanged((reason) => seen.push(reason))

    statusMock.mockResolvedValue({
      enabled: true,
      unlocked: false,
      kdf: { iter: 600000 },
    })
    unlockMock.mockImplementation(async () => {
      return { enabled: true, unlocked: true, ok: true }
    })

    render(<E2eeSettings onChanged={() => {}} />)

    const input = await screen.findByLabelText('Vault passphrase')
    await user.type(input, 'correct horse battery staple')
    await user.click(screen.getByRole('button', { name: 'Unlock' }))

    await waitFor(() =>
      expect(unlockMock).toHaveBeenCalledWith('correct horse battery staple'),
    )
    expect(seen).toEqual(['e2ee']) // bus notified so views refetch
  })

  it('surfaces a wrong-passphrase failure without notifying the bus', async () => {
    const user = userEvent.setup()
    let notifications = 0
    onVaultChanged(() => {
      notifications += 1
    })

    statusMock.mockResolvedValue({ enabled: true, unlocked: false })
    unlockMock.mockRejectedValue(new Error('unlock failed: check mismatch'))

    render(<E2eeSettings />)
    await user.type(await screen.findByLabelText('Vault passphrase'), 'wrong')
    await user.click(screen.getByRole('button', { name: 'Unlock' }))

    expect(await screen.findByText(/check mismatch/)).toBeInTheDocument()
    expect(notifications).toBe(0)
  })

  it('offers Lock while unlocked', async () => {
    const user = userEvent.setup()
    statusMock.mockResolvedValue({ enabled: true, unlocked: true, kdf: { iter: 600000 } })
    lockMock.mockResolvedValue({ ok: true, unlocked: false })
    // After locking, loadStatus re-fetches and shows the locked view again.
    statusMock.mockResolvedValueOnce({ enabled: true, unlocked: true, kdf: { iter: 600000 } })

    render(<E2eeSettings />)
    await user.click(await screen.findByRole('button', { name: 'Lock vault' }))
    await waitFor(() => expect(lockMock).toHaveBeenCalled())
  })

  it('reports KDF params for parity with the phone', async () => {
    statusMock.mockResolvedValue({
      enabled: true,
      unlocked: false,
      kdf: { alg: 'PBKDF2-HmacSHA256', iter: 600000 },
    })
    render(<E2eeSettings />)
    const dd = await screen.findByText('PBKDF2', { exact: false })
    expect(dd).toHaveTextContent('PBKDF2')
    expect(dd).toHaveTextContent('600,000 iterations') // en-US pinned in component
  })
})
