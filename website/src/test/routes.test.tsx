import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Route as RootRoute } from '#/routes/__root'
import { Route as IndexRoute } from '#/routes/index'

type Meta = { title?: string; name?: string; property?: string; content?: string; charSet?: string }
type Link = { rel: string; href: string }

const head = () => RootRoute.options.head!({} as never) as { meta: Meta[]; links: Link[] }
const homeHead = () => IndexRoute.options.head!({} as never) as { meta: Meta[]; links: Link[] }
const meta = (key: string) => head().meta.find((m) => m.name === key || m.property === key)?.content

describe('document head', () => {
  it('has a descriptive title and description', () => {
    const title = head().meta.find((m) => m.title)?.title
    expect(title).toMatch(/Portland/)
    expect(title!.length).toBeLessThanOrEqual(60)
    expect(meta('description')!.length).toBeGreaterThan(50)
    expect(meta('description')!.length).toBeLessThanOrEqual(160)
  })

  it('declares the charset and a mobile viewport that respects the notch', () => {
    expect(head().meta).toContainEqual({ charSet: 'utf-8' })
    expect(meta('viewport')).toContain('width=device-width')
    expect(meta('viewport')).toContain('viewport-fit=cover')
  })

  it('matches social previews to the page', () => {
    expect(meta('og:title')).toBe(head().meta.find((m) => m.title)?.title)
    expect(meta('og:description')).toBe(meta('description'))
    expect(meta('og:type')).toBe('website')
    expect(meta('twitter:card')).toBe('summary_large_image')
    // Crawlers ignore relative image URLs, and og:url must match the canonical link.
    expect(meta('og:image')).toBe('https://www.portland.n1m35h.in/og.jpg')
    expect(meta('og:url')).toBe('https://www.portland.n1m35h.in/')
    expect(homeHead().links.find((l) => l.rel === 'canonical')?.href).toBe(meta('og:url'))
  })

  it('links the stylesheet and icons, and preloads the hero screenshot from the home route', () => {
    const rels = head().links.map((l) => `${l.rel} ${l.href}`)
    expect(rels.some((r) => r.startsWith('stylesheet '))).toBe(true)
    expect(rels).toContain('icon /favicon.ico')
    expect(rels).toContain('apple-touch-icon /apple-touch-icon.png')
    expect(rels).toContain('manifest /site.webmanifest')
    expect(rels).toContain('alternate /llms.txt')
    const homeLinks = homeHead().links.map((l) => `${l.rel} ${l.href}`)
    expect(homeLinks).toContain('canonical https://www.portland.n1m35h.in/')
    expect(homeLinks).toContain('preload /feed.webp')
  })
})

describe('home page', () => {
  it('composes nav, hero, features and footer in order', () => {
    const Home = IndexRoute.options.component!
    const { container } = render(<Home />)

    const landmarks = [
      screen.getByRole('navigation', { name: 'Primary' }),
      screen.getByRole('main'),
      screen.getByRole('contentinfo'),
    ]
    for (let i = 1; i < landmarks.length; i++) {
      expect(landmarks[i - 1].compareDocumentPosition(landmarks[i]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    }
    const main = container.querySelector('main')!
    expect(main.querySelector('[aria-labelledby="hero-title"]')).not.toBeNull()
    expect(main.querySelector('#features')).not.toBeNull()
  })
})
