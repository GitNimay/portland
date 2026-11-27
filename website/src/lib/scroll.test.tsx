import { render } from '@testing-library/react'
import { ScrollTrigger } from 'gsap/ScrollTrigger'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Footer } from '#/components/Footer'
import { Hero } from '#/components/Hero'
import { useSmoothScroll } from './scroll'

const lenis = vi.hoisted(() => ({ instances: [] as { options: unknown; destroy: () => void }[] }))

vi.mock('lenis', () => ({
  default: class {
    options: unknown
    destroy = vi.fn()
    on = vi.fn()
    raf = vi.fn()
    constructor(options: unknown) {
      this.options = options
      lenis.instances.push(this)
    }
  },
}))

/** Report whether the visitor is fine with motion (the setup file defaults to "no"). */
function allowMotion(ok: boolean) {
  vi.spyOn(window, 'matchMedia').mockImplementation(
    (query: string) =>
      ({
        matches: ok && query.includes('no-preference'),
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(() => false),
      }) as MediaQueryList,
  )
}

function SmoothScroll() {
  useSmoothScroll()
  return null
}

describe('scroll motion', () => {
  beforeEach(() => {
    lenis.instances.length = 0
  })
  afterEach(() => {
    allowMotion(false)
  })

  it('smooths scrolling for visitors who allow motion, and tears it down after', () => {
    allowMotion(true)
    const { unmount } = render(<SmoothScroll />)
    expect(lenis.instances).toHaveLength(1)
    expect(lenis.instances[0].options).toMatchObject({ anchors: true, autoRaf: false })

    unmount()
    expect(lenis.instances[0].destroy).toHaveBeenCalled()
  })

  it('leaves native scrolling alone with reduced motion', () => {
    allowMotion(false)
    render(<SmoothScroll />)
    expect(lenis.instances).toHaveLength(0)
  })

  it('scrubs the hero and footer depth layers with motion, and reverts them on unmount', () => {
    allowMotion(true)
    const { unmount } = render(
      <>
        <Hero />
        <Footer />
      </>,
    )
    expect(ScrollTrigger.getAll()).toHaveLength(2)
    unmount()
    expect(ScrollTrigger.getAll()).toHaveLength(0)
  })

  it('creates no scroll triggers with reduced motion', () => {
    allowMotion(false)
    render(<Hero />)
    expect(ScrollTrigger.getAll()).toHaveLength(0)
  })
})
