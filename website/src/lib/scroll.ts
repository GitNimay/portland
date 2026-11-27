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

    let lastWidth = -1
    const syncHeight = () => {
      const w = document.documentElement.clientWidth
      if (w === lastWidth) return
      lastWidth = w
      el.style.setProperty('--hero-h', `${el.offsetHeight}px`)
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
          .to('.px-sky', { y: vh(0.22) }, 0)
          .to('.px-content', { y: vh(0.14), opacity: 0.35 }, 0)
          .to('.px-phone', { y: vh(0.04) }, 0)
          .to('.px-cloud-far', { y: vh(-0.02) }, 0)
          .to('.px-cloud-back', { y: vh(-0.05) }, 0)
          .to('.px-cloud-front', { y: vh(-0.08) }, 0)
          .to('.px-cloud-near', { y: vh(-0.13) }, 0)
      })
    },
    { scope },
  )
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
          .fromTo('.ft-mark', { yPercent: 45, opacity: 0.2 }, { yPercent: 0, opacity: 1 }, 0)
          .fromTo('.ft-cloud', { yPercent: 18 }, { yPercent: 0 }, 0)
      })
    },
    { scope },
  )
}
