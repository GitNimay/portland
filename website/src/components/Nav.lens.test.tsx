import { act, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type * as NavModule from '#/lib/nav'
import { DOCK_H, LENS_SCALE } from '#/lib/nav'
import { Nav } from './Nav'

// Chromium path: backdrop refraction is available and the map can be drawn.
vi.mock('#/lib/nav', async (original) => ({
  ...(await original<typeof NavModule>()),
  supportsBackdropLens: () => true,
  lensMapUrl: (w: number, h: number) => `data:image/png;map-${w}x${h}`,
}))

function scrollTo(y: number) {
  Object.defineProperty(window, 'scrollY', { configurable: true, value: y })
  act(() => {
    window.dispatchEvent(new Event('scroll'))
    vi.advanceTimersByTime(16)
  })
}

const displacement = (c: HTMLElement) => c.querySelector('feDisplacementMap')

describe('Nav lens (Chromium)', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 0 })
    Object.defineProperty(document.documentElement, 'clientWidth', { configurable: true, value: 1280 })
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('draws a lens map sized exactly to the docked pill', () => {
    const { container } = render(<Nav />)
    const image = container.querySelector('#nav-lens feImage')
    expect(image).toHaveAttribute('href', `data:image/png;map-440x${DOCK_H}`)
    expect(image).toHaveAttribute('width', '440')
    expect(container.querySelector('.nav-glass')).toHaveAttribute('data-lens')
  })

  it('keeps the backdrop unbent until the pill has finished forming', () => {
    const { container } = render(<Nav />)
    scrollTo(400)
    act(() => {
      vi.advanceTimersByTime(400)
    })
    expect(displacement(container)).toHaveAttribute('scale', '0')
  })

  it('eases the bend in after docking and drops it at once on undock', () => {
    const { container } = render(<Nav />)
    scrollTo(400)
    act(() => {
      vi.advanceTimersByTime(2000)
    })
    expect(displacement(container)).toHaveAttribute('scale', String(LENS_SCALE))

    scrollTo(0)
    expect(displacement(container)).toHaveAttribute('scale', '0')
  })

  it('redraws the map when the viewport narrows', () => {
    const { container } = render(<Nav />)
    Object.defineProperty(document.documentElement, 'clientWidth', { configurable: true, value: 390 })
    act(() => {
      window.dispatchEvent(new Event('resize'))
    })
    expect(container.querySelector('#nav-lens feImage')).toHaveAttribute('width', '366')
  })
})
