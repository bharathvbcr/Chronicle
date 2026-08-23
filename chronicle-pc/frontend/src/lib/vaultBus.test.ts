import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  notifyVaultChanged,
  onVaultChanged,
  resetVaultBus,
} from './vaultBus'

afterEach(() => resetVaultBus())

describe('vaultBus', () => {
  it('delivers notifications to subscribers with the reason', () => {
    const seen: string[] = []
    onVaultChanged((reason) => seen.push(reason))
    notifyVaultChanged('sse')
    notifyVaultChanged('e2ee')
    expect(seen).toEqual(['sse', 'e2ee'])
  })

  it('stops delivering after unsubscribe', () => {
    const seen: string[] = []
    const off = onVaultChanged((reason) => seen.push(reason))
    notifyVaultChanged('manual')
    off()
    notifyVaultChanged('manual')
    expect(seen).toEqual(['manual'])
  })

  it('defaults the reason to manual and supports multiple listeners', () => {
    const a = vi.fn()
    const b = vi.fn()
    onVaultChanged(a)
    onVaultChanged(b)
    notifyVaultChanged()
    expect(a).toHaveBeenCalledWith('manual')
    expect(b).toHaveBeenCalledWith('manual')
  })

  it('a throwing listener does not block the others', () => {
    const ok = vi.fn()
    onVaultChanged(() => {
      throw new Error('boom')
    })
    onVaultChanged(ok)
    expect(() => notifyVaultChanged('sse')).not.toThrow()
    expect(ok).toHaveBeenCalledWith('sse')
  })
})
