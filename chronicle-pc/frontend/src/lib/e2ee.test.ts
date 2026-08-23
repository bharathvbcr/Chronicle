import { describe, expect, it } from 'vitest'
import { isEntryLocked } from './e2ee'

describe('isEntryLocked', () => {
  it('is true when ciphertext present and text empty', () => {
    expect(
      isEntryLocked({ text: '', text_enc: { v: '1', nonce: 'n', ct: 'c' } }),
    ).toBe(true)
  })

  it('is false when text is readable', () => {
    expect(isEntryLocked({ text: 'hello', text_enc: null })).toBe(false)
    expect(
      isEntryLocked({ text: 'hello', text_enc: { v: '1', nonce: 'n', ct: 'c' } }),
    ).toBe(false)
  })

  it('is false for plain entries without a blob', () => {
    expect(isEntryLocked({ text: '' })).toBe(false)
    expect(isEntryLocked({ text: '', text_enc: undefined })).toBe(false)
  })

  it('treats whitespace-only text on an encrypted entry as locked', () => {
    expect(
      isEntryLocked({ text: '   ', text_enc: { v: '1', nonce: 'n', ct: 'c' } }),
    ).toBe(true)
  })
})
