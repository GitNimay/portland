import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DOWNLOAD_URL, REPO_URL } from '#/lib/links'
import { Hero } from './Hero'

describe('Hero', () => {
  it('has a single page heading with the tagline', () => {
    render(<Hero />)
    const headings = screen.getAllByRole('heading', { level: 1 })
    expect(headings).toHaveLength(1)
    expect(headings[0]).toHaveTextContent('Save what inspires you.')
    expect(headings[0]).toHaveTextContent('Keep it yours.')
  })

  it('labels the section by its heading', () => {
    render(<Hero />)
    expect(screen.getByRole('region', { name: /save what inspires you/i })).toBeInTheDocument()
  })

  it('describes the product', () => {
    render(<Hero />)
    expect(screen.getByText(/self-hosted Pinterest alternative for Android/i)).toBeInTheDocument()
  })

  it('offers Download and View code as the two calls to action', () => {
    render(<Hero />)
    expect(screen.getByRole('link', { name: 'Download' })).toHaveAttribute('href', DOWNLOAD_URL)
    expect(screen.getByRole('link', { name: 'View code' })).toHaveAttribute('href', REPO_URL)
  })

  it('loads the app screenshot eagerly, with a description', () => {
    render(<Hero />)
    const shot = screen.getByRole('img', { name: /masonry feed/i })
    expect(shot).toHaveAttribute('src', '/feed.webp')
    expect(shot).toHaveAttribute('loading', 'eager')
  })

  it('keeps the cloud bank and chips out of the accessibility tree', () => {
    const { container } = render(<Hero />)
    const decorative = container.querySelectorAll('[aria-hidden]')
    expect(decorative.length).toBeGreaterThan(0)
    // Every image is either described or explicitly decorative (empty alt).
    for (const img of container.querySelectorAll('img')) expect(img).toHaveAttribute('alt')
  })
})
