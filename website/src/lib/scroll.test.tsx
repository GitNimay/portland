import { fireEvent, render } from '@testing-library/react'
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
function allowMotion(ok: boolean, { coarse = false, mouse = false } = {}) {
  vi.spyOn(window, 'matchMedia').mockImplementation(
    (query: string) =>
      ({
        matches:
          (ok && query.includes('no-preference')) ||
          (coarse && query.includes('pointer: coarse')) ||
          (mouse && query.includes('pointer: fine')),
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

/** Queues animation frames so a test can step them one at a time. */
function manualFrames() {
  let queue: FrameRequestCallback[] = []
  vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb) => {
    queue.push(cb)
    return queue.length
  })
  vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(vi.fn())
  return {
    pending: () => queue.length,
    /** Runs frames until the loop goes idle (or `max` frames pass). */
    flush(max = 500) {
      for (let i = 0; i < max && queue.length; i++) {
        const run = queue
        queue = []
        for (const cb of run) cb(performance.now())
      }
    },
  }
}

const mouseMove = (x: number, y: number) =>
  fireEvent(window, new PointerEvent('pointermove', { clientX: x, clientY: y, pointerType: 'mouse' }))

/** Horizontal pointer drift on a layer, in px. */
const driftX = (el: HTMLElement) => parseFloat(el.style.translate)

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
    vi.spyOn(CSS, 'supports').mockReturnValue(false)
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
    vi.spyOn(CSS, 'supports').mockReturnValue(false)
    render(<Hero />)
    expect(ScrollTrigger.getAll()).toHaveLength(0)
  })

  it('skips Lenis smooth wheel ticker on touch-only devices', () => {
    allowMotion(true, { coarse: true })
    render(<SmoothScroll />)
    expect(lenis.instances).toHaveLength(0)
  })

  it('drifts the hero layers with a mouse, nearer layers further, and settles', () => {
    allowMotion(true, { mouse: true })
    const frames = manualFrames()
    const { container } = render(<Hero />)
    const sky = container.querySelector<HTMLElement>('.px-sky')!
    const near = container.querySelector<HTMLElement>('.px-cloud-near')!

    mouseMove(window.innerWidth, window.innerHeight / 2)
    frames.flush()
    expect(frames.pending()).toBe(0)
    // Right edge of the window: the near clouds follow the pointer, the sky drifts against it.
    expect(driftX(near)).toBe(28)
    expect(driftX(sky)).toBe(-10)

    fireEvent.pointerLeave(document.documentElement)
    frames.flush()
    expect(driftX(near)).toBe(0)
  })

  it('ignores touch pointers and an off-screen hero, and clears the drift on unmount', () => {
    allowMotion(true, { mouse: true })
    const frames = manualFrames()
    const { container, unmount } = render(<Hero />)
    const near = container.querySelector<HTMLElement>('.px-cloud-near')!

    fireEvent(window, new PointerEvent('pointermove', { clientX: 10, clientY: 10, pointerType: 'touch' }))
    expect(frames.pending()).toBe(0)

    const hero = container.querySelector('section')!
    hero.setAttribute('data-offscreen', '')
    mouseMove(10, 10)
    expect(frames.pending()).toBe(0)
    hero.removeAttribute('data-offscreen')

    mouseMove(0, 0)
    frames.flush(3)
    expect(near.style.translate).not.toBe('')
    unmount()
    expect(near.style.translate).toBe('')
  })

  it('leaves the hero still for touch screens and reduced motion', () => {
    allowMotion(true)
    const frames = manualFrames()
    const { container } = render(<Hero />)
    mouseMove(0, 0)
    expect(frames.pending()).toBe(0)
    expect(container.querySelector<HTMLElement>('.px-cloud-near')!.style.translate).toBe('')
  })

  it('defers parallax to CSS Scroll-Driven Animations when supported', () => {
    allowMotion(true)
    vi.spyOn(CSS, 'supports').mockImplementation((condition: string) => condition === 'animation-timeline: scroll()')
    const { container, unmount } = render(
      <>
        <Hero />
        <Footer />
      </>,
    )
    expect(ScrollTrigger.getAll()).toHaveLength(0)
    expect(container.querySelector('section')?.style.getPropertyValue('--hero-h')).toMatch(/px$/)
    unmount()
  })
})
