import { useEffect, useRef } from 'react'

/**
 * Marks the element with `data-offscreen` while it is out of view,
 * which pauses every looping chip animation inside it (see styles.css).
 */
export function useOffscreenPause<T extends HTMLElement>() {
  const ref = useRef<T>(null)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const io = new IntersectionObserver(([entry]) => {
      el.toggleAttribute('data-offscreen', !entry.isIntersecting)
    })
    io.observe(el)
    return () => io.disconnect()
  }, [])
  return ref
}
