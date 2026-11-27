import { useEffect, useState } from 'react'
import { AndroidIcon } from '#/components/icons'
import { DOWNLOAD_URL, REPO_URL } from '#/lib/links'
import { DOCK_H, DOCK_W, LENS_SCALE, dockWidth, lensMapUrl, nextDocked, supportsBackdropLens } from '#/lib/nav'

/**
 * Sticky nav. At the top of the page it is the plain, wide row over the sky. Once you scroll,
 * its items draw together and a liquid-glass pill forms around them.
 *
 * The glass follows the app's `Modifier.glass` (Theme.kt): the backdrop is saturated and
 * slightly brightened, bent by a lens at the rim, under a light film with a specular edge
 * and a soft shadow. The lens is an SVG displacement map, which only Chromium applies to
 * backdrops; elsewhere the pill is the same frosted glass without the bend.
 */
export function Nav() {
  const docked = useDocked()
  const lens = useLens(docked)

  return (
    <header className="pointer-events-none fixed inset-x-0 top-0 z-50 pt-[env(safe-area-inset-top)]">
      <LensFilter lens={lens} />
      <div
        data-docked={docked || undefined}
        className="nav-shell mx-auto"
        style={{ '--dock-w': `${lens.width}px`, '--dock-h': `${DOCK_H}px` } as React.CSSProperties}
      >
        <nav
          aria-label="Primary"
          className="nav-bar pointer-events-auto relative isolate mx-auto flex items-center justify-between"
        >
          <span
            aria-hidden
            data-lens={lens.supported || undefined}
            className="nav-glass absolute inset-0 -z-10 rounded-full"
          />
          <a href="/" className="flex items-center gap-2 rounded-full text-body font-medium">
            <span className="grid size-8 place-items-center overflow-hidden rounded-full bg-paper shadow-sm">
              <img src="/mark.png" alt="" width={432} height={432} className="size-full scale-[1.55] object-contain" />
            </span>
            Portland
          </a>
          <ul className="nav-links hidden items-center text-small sm:flex">
            <li>
              <a href="#features" className="nav-link rounded-full px-3 py-2">
                Features
              </a>
            </li>
            <li>
              <a href={REPO_URL} target="_blank" rel="noreferrer" className="nav-link rounded-full px-3 py-2">
                GitHub
              </a>
            </li>
          </ul>
          <a
            href={DOWNLOAD_URL}
            target="_blank"
            rel="noreferrer"
            className="nav-cta inline-flex h-9 items-center gap-1.5 rounded-full px-3.5 text-small font-medium whitespace-nowrap select-none active:scale-[0.96]"
          >
            <AndroidIcon className="size-3.5" />
            Download
          </a>
        </nav>
      </div>
    </header>
  )
}

/** Docked once the page has scrolled past DOCK_AT, until it comes back above UNDOCK_AT. */
function useDocked() {
  const [docked, setDocked] = useState(false)
  useEffect(() => {
    let raf = 0
    const read = () => {
      raf = 0
      const y = window.scrollY
      setDocked((d) => nextDocked(d, y))
    }
    const onScroll = () => {
      if (!raf) raf = requestAnimationFrame(read)
    }
    read()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      window.removeEventListener('scroll', onScroll)
      cancelAnimationFrame(raf)
    }
  }, [])
  return docked
}

type Lens = { supported: boolean; width: number; map: string; scale: number }

/** The pill's width transition (styles.css), after which the lens map fits it exactly. */
const SETTLE_MS = 560

/**
 * Builds the lens map for the docked pill's exact size, and only bends the backdrop once the
 * pill has finished forming: while it is still changing width the map would not fit it.
 */
function useLens(docked: boolean): Lens {
  const [lens, setLens] = useState<Lens>({ supported: false, width: DOCK_W, map: '', scale: 0 })

  // Size the pill to the viewport and redraw the map when that size changes.
  useEffect(() => {
    const supported = supportsBackdropLens()
    let last = 0
    const size = () => {
      const width = dockWidth(document.documentElement.clientWidth)
      if (width === last) return
      last = width
      setLens((l) => ({ ...l, supported, width, map: supported ? lensMapUrl(width, DOCK_H) : '' }))
    }
    size()
    window.addEventListener('resize', size)
    return () => window.removeEventListener('resize', size)
  }, [])

  // Ease the bend in after the pill settles; the cleanup drops it at once on undock.
  useEffect(() => {
    if (!lens.supported || !docked) return
    let raf = 0
    const timer = window.setTimeout(() => {
      const start = performance.now()
      const step = (now: number) => {
        const t = Math.min(1, (now - start) / 320)
        setLens((l) => ({ ...l, scale: LENS_SCALE * (1 - (1 - t) ** 3) }))
        if (t < 1) raf = requestAnimationFrame(step)
      }
      raf = requestAnimationFrame(step)
    }, SETTLE_MS)
    return () => {
      clearTimeout(timer)
      cancelAnimationFrame(raf)
      setLens((l) => ({ ...l, scale: 0 }))
    }
  }, [docked, lens.supported])

  return lens
}

function LensFilter({ lens }: { lens: Lens }) {
  if (!lens.supported || !lens.map) return null
  return (
    <svg aria-hidden width="0" height="0" className="absolute">
      <filter
        id="nav-lens"
        filterUnits="userSpaceOnUse"
        primitiveUnits="userSpaceOnUse"
        colorInterpolationFilters="sRGB"
        x="0"
        y="0"
        width={lens.width}
        height={DOCK_H}
      >
        <feImage
          href={lens.map}
          x="0"
          y="0"
          width={lens.width}
          height={DOCK_H}
          preserveAspectRatio="none"
          result="map"
        />
        <feDisplacementMap in="SourceGraphic" in2="map" scale={lens.scale} xChannelSelector="R" yChannelSelector="G" />
      </filter>
    </svg>
  )
}
