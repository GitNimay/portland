import { describe, expect, it } from 'vitest'
import { DOWNLOAD_URL, REPO_URL } from './links'

describe('links', () => {
  it('points at the public repository over https', () => {
    const url = new URL(REPO_URL)
    expect(url.protocol).toBe('https:')
    expect(url.hostname).toBe('github.com')
  })

  it('downloads the latest release of that repository', () => {
    expect(DOWNLOAD_URL).toBe(`${REPO_URL}/releases/latest`)
  })
})
