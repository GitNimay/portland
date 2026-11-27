import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DOWNLOAD_URL, REPO_URL } from '#/lib/links'
import { DOCK_AT, UNDOCK_AT } from '#/lib/nav'
import { Nav } from './Nav'

function scrollTo(y: number) {
  Object.defineProperty(window, 'scrollY', { configurable: true, value: y })
  act(() => {
    window.dispatchEvent(new Event('scroll'))
    vi.runOnlyPendingTimers()
  })
}

const primary = () => screen.getByRole('navigation', { name: 'Primary' })
const shell = () => primary().parentElement

describe('Nav', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 0 })
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('links home, to the features and to the project', () => {
    render(<Nav />)
    const nav = within(primary())
    expect(nav.getByRole('link', { name: 'Portland' })).toHaveAttribute('href', '/')
    expect(nav.getByRole('link', { name: 'Features' })).toHaveAttribute('href', '#features')
    expect(nav.getByRole('link', { name: 'GitHub' })).toHaveAttribute('href', REPO_URL)
    expect(nav.getByRole('link', { name: 'Privacy' })).toHaveAttribute('href', '/privacy')
    expect(nav.getByRole('link', { name: 'TNC' })).toHaveAttribute('href', '/tnc')
    expect(nav.getByRole('link', { name: 'Download' })).toHaveAttribute('href', DOWNLOAD_URL)
    expect(nav.getByRole('link', { name: 'Privacy' })).not.toHaveAttribute('target')
    expect(nav.getByRole('link', { name: 'TNC' })).not.toHaveAttribute('target')
  })

  it('marks the current legal page and points Features back home from there', () => {
    render(<Nav tone="paper" pathname="/tnc" />)
    expect(primary()).toHaveAttribute('data-tone', 'paper')
    expect(screen.getByRole('link', { name: 'TNC' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Privacy' })).not.toHaveAttribute('aria-current')
    expect(screen.getByRole('link', { name: 'Features' })).toHaveAttribute('href', '/#features')
  })

  it('opens external links in a new tab without leaking the referrer', () => {
    render(<Nav />)
    for (const name of ['GitHub', 'Download']) {
      const link = screen.getByRole('link', { name })
      expect(link).toHaveAttribute('target', '_blank')
      expect(link.getAttribute('rel')).toContain('noreferrer')
    }
  })

  it('starts undocked at the top of the page', () => {
    render(<Nav />)
    expect(shell()).not.toHaveAttribute('data-docked')
  })

  it('docks into the pill once the page scrolls', () => {
    render(<Nav />)
    scrollTo(DOCK_AT + 1)
    expect(shell()).toHaveAttribute('data-docked')
  })

  it('undocks only after returning near the top', () => {
    render(<Nav />)
    scrollTo(400)
    scrollTo(UNDOCK_AT + 1)
    expect(shell()).toHaveAttribute('data-docked')
    scrollTo(0)
    expect(shell()).not.toHaveAttribute('data-docked')
  })

  it('renders docked when the page loads already scrolled', () => {
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 800 })
    render(<Nav />)
    expect(shell()).toHaveAttribute('data-docked')
  })

  it('skips the lens filter where backdrops cannot be refracted', () => {
    const { container } = render(<Nav />)
    expect(container.querySelector('#nav-lens')).toBeNull()
    expect(container.querySelector('.nav-glass')).not.toHaveAttribute('data-lens')
  })

  it('glides one hover highlight to the link under the mouse, and hides it on leave', () => {
    render(<Nav />)
    const list = screen.getByRole('link', { name: 'GitHub' }).closest('ul')!
    fireEvent.pointerOver(screen.getByRole('link', { name: 'GitHub' }), { pointerType: 'mouse' })
    expect(list).toHaveAttribute('data-hl')
    expect(list.style.getPropertyValue('--hl-x')).toMatch(/px$/)
    expect(list.style.getPropertyValue('--hl-w')).toMatch(/px$/)

    fireEvent.pointerLeave(list, { pointerType: 'mouse' })
    expect(list).not.toHaveAttribute('data-hl')
  })

  it('keeps the hover highlight for the mouse only', () => {
    render(<Nav />)
    const link = screen.getByRole('link', { name: 'Privacy' })
    fireEvent.pointerOver(link, { pointerType: 'touch' })
    expect(link.closest('ul')).not.toHaveAttribute('data-hl')
  })

  it('marks the pill settled only after it has finished forming', () => {
    render(<Nav />)
    scrollTo(DOCK_AT + 1)
    expect(shell()).not.toHaveAttribute('data-settled')
    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(shell()).toHaveAttribute('data-settled')
    scrollTo(0)
    expect(shell()).not.toHaveAttribute('data-settled')
  })

  it('stops listening to scroll and resize when unmounted', () => {
    const remove = vi.spyOn(window, 'removeEventListener')
    const { unmount } = render(<Nav />)
    unmount()
    expect(remove).toHaveBeenCalledWith('scroll', expect.any(Function))
    expect(remove).toHaveBeenCalledWith('resize', expect.any(Function))
  })
})
