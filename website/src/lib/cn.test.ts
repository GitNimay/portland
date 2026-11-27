import { describe, expect, it } from 'vitest'
import { cn } from './cn'

describe('cn', () => {
  it('joins truthy class names and drops falsy ones', () => {
    expect(cn('a', false, undefined, 'b', null, '')).toBe('a b')
  })

  it('lets later utilities win over conflicting earlier ones', () => {
    expect(cn('px-4 py-2', 'px-6')).toBe('py-2 px-6')
  })

  it('treats the custom type scale as font sizes, not colours', () => {
    // Without the extension, tailwind-merge would read text-small as a colour and drop text-ink.
    expect(cn('text-body text-ink', 'text-small')).toBe('text-ink text-small')
  })
})
