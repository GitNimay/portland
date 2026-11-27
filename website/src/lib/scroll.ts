import { useEffect, type RefObject } from 'react'
import gsap from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'
import { useGSAP } from '@gsap/react'
import Lenis from 'lenis'

gsap.registerPlugin(ScrollTrigger, useGSAP)

/** Everything here is decoration, so it only runs for people who are fine with motion. */
const MOTION_OK = '(prefers-reduced-motion: no-preference)'

/**
 * Smooth scrolling. Lenis eases the real, native scroll position (so keyboard, scrollbar,
 * find-in-page and anchors all keep working) and is ticked by GSAP's own clock, so
 * ScrollTrigger reads exactly the frame Lenis just wrote. Touch keeps native momentum.
 */
export function useSmoothScroll() {
  useEffect(() => {
    if (!matchMedia(MOTION_OK).matches) return

    const lenis = new Lenis({ lerp: 0.085, anchors: true, autoRaf: false })
    const tick = (time: number) => lenis.raf(time * 1000)

    lenis.on('scroll', () => ScrollTrigger.update())
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
 * Hero depth. One scrubbed timeline over the hero's height; every layer moves at its own
 * rate, so they separate in depth: sky slowest, the front cloud fastest and rising.
 * All layers are wrappers, so these transforms never fight a child's own transform.
 */
export function useHeroParallax(scope: RefObject<HTMLElement | null>) {
  useGSAP(
    () => {
      gsap.matchMedia().add(MOTION_OK, () => {
        gsap
          .timeline({
            defaults: { ease: 'none' },
            scrollTrigger: {
              trigger: scope.current,
              start: 'top top',
              end: 'bottom top',
              scrub: 0.6,
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
 * settles in front of it, the hero's motif played in reverse.
 */
export function useFooterMotion(scope: RefObject<HTMLElement | null>) {
  useGSAP(
    () => {
      gsap.matchMedia().add(MOTION_OK, () => {
        gsap
          .timeline({
            defaults: { ease: 'none' },
            scrollTrigger: { trigger: scope.current, start: 'top bottom', end: 'bottom bottom', scrub: 0.8 },
          })
          .fromTo('.ft-mark', { yPercent: 45, opacity: 0.2 }, { yPercent: 0, opacity: 1 }, 0)
          .fromTo('.ft-cloud', { yPercent: 18 }, { yPercent: 0 }, 0)
      })
    },
    { scope },
  )
}
