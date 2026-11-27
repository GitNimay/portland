import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DOWNLOAD_URL, REPO_URL } from '#/lib/links'
import { Footer } from './Footer'

const footerNav = () => within(screen.getByRole('navigation', { name: 'Footer' }))

describe('Footer', () => {
  it('renders as the page footer', () => {
    render(<Footer />)
    expect(screen.getByRole('contentinfo')).toBeInTheDocument()
  })

  it('groups links under Product and Open source', () => {
    render(<Footer />)
    const nav = footerNav()
    expect(nav.getAllByRole('heading').map((h) => h.textContent)).toEqual(['Product', 'Open source'])
    expect(nav.getByRole('link', { name: 'Features' })).toHaveAttribute('href', '#features')
    expect(nav.getByRole('link', { name: 'Download' })).toHaveAttribute('href', DOWNLOAD_URL)
    expect(nav.getByRole('link', { name: 'Releases' })).toHaveAttribute('href', `${REPO_URL}/releases`)
    expect(nav.getByRole('link', { name: 'GitHub' })).toHaveAttribute('href', REPO_URL)
    expect(nav.getByRole('link', { name: 'Report an issue' })).toHaveAttribute('href', `${REPO_URL}/issues`)
  })

  it('opens only external links in a new tab, without leaking the referrer', () => {
    render(<Footer />)
    for (const link of footerNav().getAllByRole('link')) {
      if (link.getAttribute('href')?.startsWith('http')) {
        expect(link).toHaveAttribute('target', '_blank')
        expect(link.getAttribute('rel')).toContain('noreferrer')
      } else {
        expect(link).not.toHaveAttribute('target')
      }
    }
  })

  it('keeps the large wordmark artwork decorative', () => {
    const { container } = render(<Footer />)
    expect(container.querySelector('svg.ft-mark')?.closest('[aria-hidden]')).not.toBeNull()
  })

  it('shows the copyright line', () => {
    render(<Footer />)
    expect(screen.getByText(/© \d{4} Portland/)).toBeInTheDocument()
  })
})
