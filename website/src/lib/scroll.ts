import { useEffect, type RefObject } from 'react'
import gsap from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'
import { useGSAP } from '@gsap/react'
import Lenis from 'lenis'

gsap.registerPlugin(ScrollTrigger, useGSAP)

if (typeof window !== 'undefined') {
  // Ignore mobile browser URL-bar show/hide height changes so scroll parallax never jumps mid-swipe.
  ScrollTrigger.config({ ignoreMobileResize: true })
}

/** Everything here is decoration, so it only runs for people who are fine with motion. */
const MOTION_OK = '(prefers-reduced-motion: no-preference)'
const COARSE_POINTER = '(pointer: coarse)'

/** Compositor-driven CSS Scroll-Driven Animations run off the main thread in modern Chromium/Safari. */
export function supportsScrollTimeline() {
  return (
    typeof CSS !== 'undefined' && typeof CSS.supports === 'function' && CSS.supports('animation-timeline: scroll()')
  )
}

function isTouchDevice() {
  return matchMedia(COARSE_POINTER).matches && !matchMedia('(pointer: fine)').matches
}

/**
 * Smooth scrolling. On desktop/fine-pointer devices, Lenis eases the real, native scroll
 * position and is ticked by GSAP's clock so ScrollTrigger reads the exact frame Lenis wrote.
 * Touch devices keep native momentum without an idle rAF ticker loop.
 */
export function useSmoothScroll() {
  useEffect(() => {
    if (!matchMedia(MOTION_OK).matches) return
    if (isTouchDevice()) return

    const lenis = new Lenis({ lerp: 0.085, anchors: true, autoRaf: false })
    const tick = (time: number) => lenis.raf(time * 1000)

    if (!supportsScrollTimeline()) {
      lenis.on('scroll', () => ScrollTrigger.update())
    }
    gsap.ticker.add(tick)
    gsap.ticker.lagSmoothing(0)

    return () => {
      gsap.ticker.remove(tick)
      lenis.destroy()
    }
  }, [])
}

const vh = (n: number) => () => window.innerHeight * n

/**
 * Hero depth. When CSS Scroll-Driven Animations are supported, sets `--hero-h` so the
 * entire parallax runs on the GPU compositor thread at 60/120fps; otherwise falls back to
 * a GSAP ScrollTrigger timeline.
 */
export function useHeroParallax(scope: RefObject<HTMLElement | null>) {
  useEffect(() => {
    const el = scope.current
    if (!el || !supportsScrollTimeline() || !matchMedia(MOTION_OK).matches) return

    // Only the hero's own height matters: the phone and clouds grow with the window on wide
    // screens, while a phone's URL bar showing or hiding never changes it.
    let lastHeight = -1
    const syncHeight = () => {
      const h = el.offsetHeight
      if (h === lastHeight) return
      lastHeight = h
      el.style.setProperty('--hero-h', `${h}px`)
    }
    syncHeight()

    const ro = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(syncHeight) : null
    ro?.observe(el)
    return () => ro?.disconnect()
  }, [scope])

  useGSAP(
    () => {
      if (supportsScrollTimeline()) return
      gsap.matchMedia().add(MOTION_OK, () => {
        const scrub = isTouchDevice() ? true : 0.6
        gsap
          .timeline({
            defaults: { ease: 'none', force3D: true },
            scrollTrigger: {
              trigger: scope.current,
              start: 'top top',
              end: 'bottom top',
              scrub,
              invalidateOnRefresh: true,
            },
          })
          .to('.px-sky', { y: vh(0.3), scale: 1.08 }, 0)
          .to('.px-content', { y: vh(0.24), scale: 0.94 }, 0)
          .to('.px-content', { opacity: 0, duration: 0.65 }, 0)
          .to('.px-phone', { y: vh(0.07), scale: 0.97 }, 0)
          .to('.px-cloud-far', { y: vh(-0.04) }, 0)
          .to('.px-cloud-back', { y: vh(-0.09) }, 0)
          .to('.px-cloud-front', { y: vh(-0.15), scale: 1.04 }, 0)
          .to('.px-cloud-near', { y: vh(-0.24), scale: 1.12 }, 0)
      })
    },
    { scope },
  )
}

/**
 * How far each hero layer drifts with the pointer, in px at the window's edge. Far layers move
 * least. The phone stays put: its glass chips would otherwise re-blur a moving backdrop every frame.
 */
const POINTER_DEPTH: [selector: string, px: number][] = [
  ['.px-sky', -10],
  ['.px-cloud-far', 6],
  ['.px-cloud-back', 12],
  ['.px-cloud-front', 18],
  ['.px-cloud-near', 28],
]

/**
 * Hero depth that follows the pointer on desktop: the sky drifts against the cursor and each
 * cloud row with it, nearer rows further, eased so it glides rather than tracks. It writes the
 * `translate` property, which composes with the scroll parallax's `transform`, and goes idle
 * once the layers settle or the hero scrolls away.
 */
export function useHeroPointer(scope: RefObject<HTMLElement | null>) {
  useEffect(() => {
    const el = scope.current
    if (!el || !matchMedia(MOTION_OK).matches || !matchMedia('(hover: hover) and (pointer: fine)').matches) return

    const layers = POINTER_DEPTH.flatMap(([sel, px]) => {
      const node = el.querySelector<HTMLElement>(sel)
      return node ? [{ node, px }] : []
    })
    let target = { x: 0, y: 0 }
    const now = { x: 0, y: 0 }
    let raf = 0

    const step = () => {
      now.x += (target.x - now.x) * 0.06
      now.y += (target.y - now.y) * 0.06
      const settled = Math.abs(target.x - now.x) < 0.001 && Math.abs(target.y - now.y) < 0.001
      if (settled) Object.assign(now, target)
      for (const { node, px } of layers) {
        node.style.translate = `${(now.x * px).toFixed(2)}px ${(now.y * px * 0.45).toFixed(2)}px`
      }
      raf = settled ? 0 : requestAnimationFrame(step)
    }
    const aim = (x: number, y: number) => {
      target = { x, y }
      if (!raf) raf = requestAnimationFrame(step)
    }
    const onMove = (e: PointerEvent) => {
      // `data-offscreen` comes from the hero's IntersectionObserver, so this never forces layout.
      if (e.pointerType !== 'mouse' || el.hasAttribute('data-offscreen')) return
      aim((e.clientX / window.innerWidth) * 2 - 1, (e.clientY / window.innerHeight) * 2 - 1)
    }
    const onLeave = () => aim(0, 0)

    window.addEventListener('pointermove', onMove, { passive: true })
    document.documentElement.addEventListener('pointerleave', onLeave)
    return () => {
      window.removeEventListener('pointermove', onMove)
      document.documentElement.removeEventListener('pointerleave', onLeave)
      cancelAnimationFrame(raf)
      for (const { node } of layers) node.style.translate = ''
    }
  }, [scope])
}

/**
 * Footer. As the page ends, the wordmark rises out of the clouds and the cloud bank
 * settles in front of it. Uses CSS `view-timeline` on the compositor when supported,
 * with a GSAP ScrollTrigger fallback.
 */
export function useFooterMotion(scope: RefObject<HTMLElement | null>) {
  useGSAP(
    () => {
      if (supportsScrollTimeline()) return
      gsap.matchMedia().add(MOTION_OK, () => {
        const scrub = isTouchDevice() ? true : 0.8
        gsap
          .timeline({
            defaults: { ease: 'none', force3D: true },
            scrollTrigger: { trigger: scope.current, start: 'top bottom', end: 'bottom bottom', scrub },
          })
          .fromTo('.ft-mark', { yPercent: 55, scale: 0.94, opacity: 0 }, { yPercent: 0, scale: 1, opacity: 1 }, 0)
          .fromTo('.ft-cloud-back', { yPercent: 10 }, { yPercent: 0 }, 0)
          .fromTo('.ft-cloud', { yPercent: 26 }, { yPercent: 0 }, 0)
      })
    },
    { scope },
  )
}
