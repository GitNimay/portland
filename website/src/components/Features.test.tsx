import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Features } from './Features'

const cards = [
  'Fluid as glass',
  'Find anything',
  'Boards that stay tidy',
  'Your bucket, your backups',
  'One tap from any app',
]

describe('Features', () => {
  it('is the #features anchor the nav links to', () => {
    const { container } = render(<Features />)
    expect(container.querySelector('section#features')).toBeInTheDocument()
  })

  it('reads the statement as one plain heading', () => {
    render(<Features />)
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent(
      'Save anything in a single tap, find it again by colour and keep it all in a bucket you own.',
    )
  })

  it('lists every feature card in order, each with a title and description', () => {
    render(<Features />)
    expect(screen.getAllByRole('listitem')).toHaveLength(cards.length)
    const titles = screen.getAllByRole('heading', { level: 3 })
    expect(titles.map((h) => h.textContent)).toEqual(cards)
    for (const title of titles) expect(title.nextElementSibling?.textContent?.length).toBeGreaterThan(20)
  })

  it('has no scroll-driven motion hooks on the section', () => {
    const { container } = render(<Features />)
    expect(container.querySelectorAll('.reveal, .drift, .word, [data-stagger]')).toHaveLength(0)
  })

  it('moves the cursor light under a mouse, but not under touch', () => {
    vi.useFakeTimers()
    const { container } = render(<Features />)
    const card = container.querySelector('article')!
    const light = card.querySelector<HTMLElement>('span[aria-hidden]:nth-of-type(2)')!

    fireEvent.pointerMove(card, { pointerType: 'touch', clientX: 40, clientY: 60 })
    vi.runOnlyPendingTimers()
    expect(light.style.translate).toBe('')

    fireEvent.pointerMove(card, { pointerType: 'mouse', clientX: 40, clientY: 60 })
    vi.runOnlyPendingTimers()
    expect(light.style.translate).toBe('40px 60px')
    vi.useRealTimers()
  })

  it('describes every app screenshot and lazy-loads it', () => {
    const { container } = render(<Features />)
    const shots = [...container.querySelectorAll('img')].filter((img) => img.getAttribute('src')?.endsWith('.webp'))
    expect(shots.length).toBeGreaterThanOrEqual(4)
    for (const shot of shots) {
      expect(shot.getAttribute('alt')).not.toBe('')
      expect(shot).toHaveAttribute('loading', 'lazy')
    }
  })
})
