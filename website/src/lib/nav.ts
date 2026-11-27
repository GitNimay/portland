/** Docked pill: width on wide screens, and its height. Narrow screens use the viewport minus a gutter. */
export const DOCK_W = 440
export const DOCK_H = 52
export const GUTTER = 12

/** Scroll offsets that dock and undock the nav. The gap between them stops it flickering at rest. */
export const DOCK_AT = 48
export const UNDOCK_AT = 16

/** Rim refraction strength once docked, in px (the app's lens bends up to ~40dp). */
export const LENS_SCALE = 34

/** Whether the nav should be docked at scroll offset `y`, given whether it is docked now. */
export function nextDocked(docked: boolean, y: number) {
  return docked ? y > UNDOCK_AT : y > DOCK_AT
}

/** Docked pill width for a viewport `width` px wide (scrollbar excluded). */
export function dockWidth(width: number) {
  return Math.max(0, Math.min(DOCK_W, width - GUTTER * 2))
}

/**
 * RGBA displacement map for a capsule `w`×`h` px. Inside a band along the rim, each pixel
 * points the backdrop sample further inward (red = x, green = y, 128 = no shift), so content
 * near the edge is magnified and bends round the curve, like light through a thick glass
 * edge. The centre and everything outside the capsule stay neutral.
 */
export function lensDisplacement(w: number, h: number) {
  const data = new Uint8ClampedArray(w * h * 4)
  const r = h / 2
  const band = Math.min(r * 0.9, 20)
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const px = x + 0.5
      const py = y + 0.5
      const dx = px - Math.min(Math.max(px, r), w - r)
      const dy = py - r
      const dist = Math.hypot(dx, dy)
      const depth = r - dist // distance in from the rim
      let red = 128
      let green = 128
      if (depth > 0 && depth < band && dist > 0) {
        const t = 1 - depth / band // 0 at the band's inner edge, 1 at the rim
        const m = t * t * (3 - 2 * t) // smooth onset, strongest at the rim
        red = 128 - (dx / dist) * m * 127
        green = 128 - (dy / dist) * m * 127
      }
      const i = (y * w + x) * 4
      data[i] = red
      data[i + 1] = green
      data[i + 2] = 128
      data[i + 3] = 255
    }
  }
  return data
}

/** The lens map as a PNG data URL, for an SVG `feImage`. Empty where canvas is unavailable. */
export function lensMapUrl(w: number, h: number) {
  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  const ctx = canvas.getContext('2d')
  if (!ctx) return ''
  ctx.putImageData(new ImageData(lensDisplacement(w, h), w, h), 0, 0)
  return canvas.toDataURL()
}

/** Chromium is the only engine that runs SVG filters on a backdrop. */
export function supportsBackdropLens() {
  if (matchMedia('(prefers-reduced-transparency: reduce)').matches) return false
  const brands = (navigator as Navigator & { userAgentData?: { brands: { brand: string }[] } }).userAgentData?.brands
  return !!brands?.some((b) => b.brand === 'Chromium')
}
