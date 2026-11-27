import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { REPO_URL } from '#/lib/links'
import { PrivacyPage, TncPage } from '#/components/LegalPage'

const primary = () => screen.getByRole('navigation', { name: 'Primary' })

describe('Privacy page', () => {
  it('reads as a single editorial page with the site nav', () => {
    const { container } = render(<PrivacyPage />)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/on your device/)
    expect(screen.getByText('Privacy Policy')).toBeInTheDocument()
    expect(container.querySelectorAll('mark')).toHaveLength(2)
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual([
      'The app',
      'On this phone',
      'Your bucket',
      'Descriptions',
      'Links you save',
      'Updates',
      'This website',
      'Changes',
    ])
    expect(within(primary()).getByRole('link', { name: 'Privacy' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('navigation', { name: 'Related' }).querySelector('a[href="/llms.txt"]')).not.toBeNull()
    expect(screen.getByText(/Updated 27 November 2026/)).toBeInTheDocument()
  })
})

describe('TNC page', () => {
  it('uses the short name and states the MIT license', () => {
    const { container } = render(<TncPage />)
    expect(container.textContent).not.toMatch(/terms and conditions/i)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/free software/)
    expect(screen.getAllByText('TNC').length).toBeGreaterThan(0)
    expect(screen.getByRole('link', { name: 'license file' })).toHaveAttribute('href', `${REPO_URL}/blob/main/LICENSE`)
    expect(screen.getByRole('link', { name: 'GitHub private advisories' })).toHaveAttribute(
      'href',
      `${REPO_URL}/security/advisories/new`,
    )
    expect(within(primary()).getByRole('link', { name: 'TNC' })).toHaveAttribute('aria-current', 'page')
    const levels = [...container.querySelectorAll('h1, h2')].map((h) => Number(h.tagName[1]))
    for (let i = 1; i < levels.length; i++) expect(levels[i] - levels[i - 1]).toBeLessThanOrEqual(1)
  })

  it('opens only off-site links in a new tab', () => {
    render(<TncPage />)
    for (const link of screen.getAllByRole('link')) {
      if (link.getAttribute('href')?.startsWith('http')) {
        expect(link).toHaveAttribute('target', '_blank')
        expect(link.getAttribute('rel')).toContain('noreferrer')
      } else {
        expect(link).not.toHaveAttribute('target')
      }
    }
  })
})
