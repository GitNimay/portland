import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  DOCK_AT,
  DOCK_H,
  DOCK_W,
  GUTTER,
  UNDOCK_AT,
  dockShift,
  dockWidth,
  floatWidth,
  lensDisplacement,
  nextDocked,
  supportsBackdropLens,
} from './nav'

describe('nextDocked', () => {
  it('stays undocked until the page scrolls past the dock threshold', () => {
    expect(nextDocked(false, 0)).toBe(false)
    expect(nextDocked(false, DOCK_AT)).toBe(false)
    expect(nextDocked(false, DOCK_AT + 1)).toBe(true)
  })

  it('stays docked until the page returns above the undock threshold', () => {
    expect(nextDocked(true, DOCK_AT)).toBe(true)
    expect(nextDocked(true, UNDOCK_AT + 1)).toBe(true)
    expect(nextDocked(true, UNDOCK_AT)).toBe(false)
  })

  it('does not flicker for offsets between the two thresholds', () => {
    const y = (DOCK_AT + UNDOCK_AT) / 2
    expect(nextDocked(false, y)).toBe(false)
    expect(nextDocked(true, y)).toBe(true)
  })
})

describe('floatWidth', () => {
  it('spans the wide bar, just outside the brand and Download', () => {
    // 1024px bar, 24px padding, 10px of glass past each item.
    expect(floatWidth(1440, DOCK_W)).toBe(1024 - 48 + 20)
    expect(floatWidth(900, DOCK_W)).toBe(900 - 48 + 20)
  })

  it('never starts narrower than the docked pill', () => {
    expect(floatWidth(390, dockWidth(390))).toBe(390 - 32 + 20)
    expect(floatWidth(300, 600)).toBe(600)
  })
})

describe('dockWidth', () => {
  it('caps the pill at its desktop width', () => {
    expect(dockWidth(1440)).toBe(DOCK_W)
    expect(dockWidth(DOCK_W + GUTTER * 2)).toBe(DOCK_W)
  })

  it('fills narrow viewports minus a gutter on each side', () => {
    expect(dockWidth(390)).toBe(390 - GUTTER * 2)
  })

  it('never goes negative', () => {
    expect(dockWidth(10)).toBe(0)
  })
})

describe('lensDisplacement', () => {
  const w = 200
  const h = DOCK_H
  const map = lensDisplacement(w, h)
  const at = (x: number, y: number) => {
    const i = (y * w + x) * 4
    return { r: map[i], g: map[i + 1], b: map[i + 2], a: map[i + 3] }
  }

  it('is one opaque RGBA pixel per point', () => {
    expect(map).toHaveLength(w * h * 4)
    expect(map.filter((_, i) => i % 4 === 3).every((a) => a === 255)).toBe(true)
  })

  it('leaves the centre of the pill neutral', () => {
    expect(at(w / 2, h / 2)).toEqual({ r: 128, g: 128, b: 128, a: 255 })
  })

  it('samples inward near the rim', () => {
    // Just inside the top edge the shift points down (green above neutral), not sideways.
    const top = at(w / 2, 1)
    expect(top.g).toBeGreaterThan(128)
    expect(top.r).toBe(128)
    // Just inside the left cap the shift points right (red above neutral).
    expect(at(1, h / 2).r).toBeGreaterThan(128)
  })

  it('is mirror-symmetric across both axes', () => {
    const a = at(3, 5)
    const b = at(w - 4, h - 6)
    expect(Math.abs(a.r + b.r - 256)).toBeLessThanOrEqual(2)
    expect(Math.abs(a.g + b.g - 256)).toBeLessThanOrEqual(2)
  })
})

describe('dockShift', () => {
  it('computes inward travel for desktop and mobile viewports without going negative', () => {
    expect(dockShift(1440, DOCK_W)).toEqual({ left: 218, right: 216 })
    expect(dockShift(390, dockWidth(390))).toEqual({ left: 6, right: 4 })
    expect(dockShift(320, 320)).toEqual({ left: 0, right: 0 })
  })
})

describe('supportsBackdropLens', () => {
  const uaDescriptor = Object.getOwnPropertyDescriptor(navigator, 'userAgentData')

  afterEach(() => {
    vi.restoreAllMocks()
    if (uaDescriptor) {
      Object.defineProperty(navigator, 'userAgentData', uaDescriptor)
    } else {
      delete (navigator as { userAgentData?: unknown }).userAgentData
    }
  })

  function mockEnvironment({ reducedTransparency = false, finePointer = true, chromium = true } = {}) {
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (query: string) =>
        ({
          matches:
            (reducedTransparency && query.includes('prefers-reduced-transparency')) ||
            (finePointer && query.includes('pointer: fine')),
          media: query,
          onchange: null,
          addListener: vi.fn(),
          removeListener: vi.fn(),
          addEventListener: vi.fn(),
          removeEventListener: vi.fn(),
          dispatchEvent: vi.fn(() => false),
        }) as MediaQueryList,
    )
    Object.defineProperty(navigator, 'userAgentData', {
      configurable: true,
      value: chromium ? { brands: [{ brand: 'Chromium' }] } : { brands: [{ brand: 'Firefox' }] },
    })
  }

  it('enables the lens only on desktop fine-pointer Chromium without reduced transparency', () => {
    mockEnvironment({ finePointer: true, chromium: true })
    expect(supportsBackdropLens()).toBe(true)

    mockEnvironment({ finePointer: false, chromium: true })
    expect(supportsBackdropLens()).toBe(false)

    mockEnvironment({ reducedTransparency: true, finePointer: true, chromium: true })
    expect(supportsBackdropLens()).toBe(false)

    mockEnvironment({ finePointer: true, chromium: false })
    expect(supportsBackdropLens()).toBe(false)
  })
})
