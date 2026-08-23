import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { resetVaultBus } from '../lib/vaultBus'

vi.mock('../api/client', () => ({
  api: {
    devices: { list: vi.fn(), revoke: vi.fn() },
  },
}))

import { api } from '../api/client'
import { PairedDevices } from './PairedDevices'

const listMock = vi.mocked(api.devices.list)
const revokeMock = vi.mocked(api.devices.revoke)

beforeEach(() => {
  vi.clearAllMocks()
  resetVaultBus()
})

afterEach(() => cleanup())

describe('PairedDevices', () => {
  it('lists paired devices with their pairing date', async () => {
    listMock.mockResolvedValue({
      devices: [{ name: 'phone', created: '2026-08-01T10:00:00+00:00' }],
    })
    render(<PairedDevices />)
    expect(await screen.findByText('phone')).toBeInTheDocument()
    expect(screen.getByText(/paired/)).toBeInTheDocument()
  })

  it('revokes a device after confirm and reloads the list', async () => {
    const user = userEvent.setup()
    listMock
      .mockResolvedValueOnce({ devices: [{ name: 'phone', created: null }] })
      .mockResolvedValueOnce({ devices: [] })
    revokeMock.mockResolvedValue({ ok: true, revoked: 'phone' })

    render(<PairedDevices />)
    await user.click(await screen.findByRole('button', { name: 'Revoke…' }))
    await user.click(screen.getByRole('button', { name: 'Revoke' }))

    await waitFor(() => expect(revokeMock).toHaveBeenCalledWith('phone'))
    expect(await screen.findByText(/No devices paired/)).toBeInTheDocument()
  })

  it('surfaces revocation failures instead of silently reloading', async () => {
    const user = userEvent.setup()
    listMock.mockResolvedValue({ devices: [{ name: 'phone', created: null }] })
    revokeMock.mockRejectedValue(new Error('no such device'))

    render(<PairedDevices />)
    await user.click(await screen.findByRole('button', { name: 'Revoke…' }))
    await user.click(screen.getByRole('button', { name: 'Revoke' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('no such device')
  })
})
