import { cn } from '#/lib/cn'

/**
 * Placement of a cloud on wide screens, in stage units (`--u`, see styles.css):
 * `x` is the cloud's centre from the middle of the screen, `b` its bottom edge, `w` its width.
 * The stage unit stops growing on big monitors, so the cloud bank keeps its proportions
 * instead of swelling with the window; extra clouds out at the flanks fill ultra-wide screens.
 */
export type Wide = { x: number; b: number; w: number }

type Props = {
  /** Phone placement (below `sm`), as utility classes. Omit to show the cloud on wide screens only. */
  narrow?: string
  wide: Wide
  flip?: boolean
  /** Drift in from this side on first paint. */
  from?: 'left' | 'right' | 'below'
  lazy?: boolean
  /**
   * Flank clouds only render where the window is wide enough to reach them (styles.css): tier 1
   * on ultra-wide or very wide windows, tier 2 beyond that. Elsewhere they would be drawn off
   * screen or under other clouds, which costs frames for nothing.
   */
  tier?: 1 | 2
}

const SRC = '/hero/cloud-a.webp'

/** A cloud with its flat underside pre-faded into the image alpha, so it dissolves without runtime masks. */
export function Cloud({ narrow, wide, flip, from, lazy, tier }: Props) {
  return (
    <img
      src={SRC}
      alt=""
      width={1120}
      height={403}
      loading={lazy ? 'lazy' : undefined}
      decoding="async"
      data-from={from}
      data-tier={tier}
      style={{ '--x': wide.x, '--b': wide.b, '--w': wide.w } as React.CSSProperties}
      className={cn('cloud absolute max-w-none', narrow ?? 'hidden sm:block', flip && '-scale-x-100')}
    />
  )
}
