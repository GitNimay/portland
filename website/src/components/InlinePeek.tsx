import { useRef, useState } from 'react'
import { AnimatePresence, motion, type Transition, type Variants } from 'motion/react'
import { CheckIcon, CloudIcon, SearchIcon } from '#/components/icons'
import { cn } from '#/lib/cn'

/*
 * Inline objects in the feature statement. Each sits on the text like an emoji with no
 * tile behind it; hovering (or tapping) it wiggles the object and pops a tiny app preview
 * above it, built from the real screenshots. Decorative only, so it is aria-hidden.
 */

const spring: Transition = { type: 'spring', stiffness: 420, damping: 28, mass: 0.8 }

const card: Variants = {
  hidden: { opacity: 0, y: 10, scale: 0.92 },
  show: { opacity: 1, y: 0, scale: 1, transition: { ...spring, staggerChildren: 0.06, delayChildren: 0.05 } },
  exit: { opacity: 0, y: 6, scale: 0.96, transition: { duration: 0.14 } },
}

const item: Variants = {
  hidden: { opacity: 0, y: 8, scale: 0.85 },
  show: { opacity: 1, y: 0, scale: 1, transition: spring },
}

export type PeekKind = 'saved' | 'colour' | 'bucket'

export function InlinePeek({ kind, tilt }: { kind: PeekKind; tilt: number }) {
  const [open, setOpen] = useState(false)
  // Mice open the peek by hovering, so their clicks must not toggle it shut again.
  const pointer = useRef('')

  return (
    <span
      aria-hidden
      className="relative inline-block align-middle"
      onPointerEnter={(e) => e.pointerType === 'mouse' && setOpen(true)}
      onPointerLeave={(e) => e.pointerType === 'mouse' && setOpen(false)}
      onPointerDown={(e) => (pointer.current = e.pointerType)}
      onClick={() => pointer.current !== 'mouse' && setOpen((o) => !o)}
    >
      <motion.span
        className="-my-[0.12em] inline-grid size-[1.1em] cursor-pointer place-items-center leading-none"
        initial={{ rotate: tilt }}
        animate={
          open
            ? { rotate: [tilt, -tilt * 1.6, tilt * 1.2, 0], scale: 1.18, y: '-0.06em' }
            : { rotate: tilt, scale: 1, y: 0 }
        }
        transition={open ? { rotate: { duration: 0.5 }, default: spring } : spring}
      >
        <Trigger kind={kind} />
      </motion.span>

      <AnimatePresence>
        {open && (
          <motion.span
            key="peek"
            variants={card}
            initial="hidden"
            animate="show"
            exit="exit"
            style={{ originY: 1 }}
            className="glass-card absolute bottom-[calc(100%+0.25em)] left-1/2 z-30 block w-max -translate-x-1/2 rounded-[22px] p-2.5 text-left text-small leading-normal font-normal tracking-normal text-ink"
          >
            {kind === 'saved' && <SavedPeek />}
            {kind === 'colour' && <ColourPeek />}
            {kind === 'bucket' && <BucketPeek />}
          </motion.span>
        )}
      </AnimatePresence>
    </span>
  )
}

function Trigger({ kind }: { kind: PeekKind }) {
  if (kind === 'colour') {
    return (
      <span className="grid grid-cols-2 gap-[0.07em]">
        {['#1a74c9', '#ff5a4e', '#34c759', '#ffc83d'].map((c) => (
          <span
            key={c}
            className="size-[0.34em] rounded-full shadow-[inset_0_-0.03em_0.05em_rgb(0_0_0/0.18)]"
            style={{ background: c }}
          />
        ))}
      </span>
    )
  }
  return (
    <img
      src={kind === 'saved' ? '/hero/obj-photos.png' : '/hero/obj-bucket.png'}
      alt=""
      loading="lazy"
      className="size-full object-contain drop-shadow-[0_0.06em_0.08em_rgb(15_40_80/0.18)]"
    />
  )
}

/** A crop of a real 720×1616 app screenshot, sized like object-cover. */
function Crop({
  src,
  x,
  y,
  w,
  size,
  h = size,
  className,
}: {
  src: string
  x: number
  y: number
  w: number
  size: number
  h?: number
  className?: string
}) {
  const k = size / w
  return (
    <span
      className={cn('block shrink-0 overflow-hidden rounded-xl bg-sky-100', className)}
      style={{
        width: size,
        height: h,
        backgroundImage: `url(${src})`,
        backgroundSize: `${720 * k}px auto`,
        backgroundPosition: `-${x * k}px -${y * k}px`,
      }}
    />
  )
}

/** Saved: three real pins fan out, then a quiet "Saved to Inspo" with a tick. */
function SavedPeek() {
  const pins = [
    { x: 28, y: 1060, rotate: -9 },
    { x: 369, y: 447, rotate: 0 },
    { x: 369, y: 780, rotate: 9 },
  ]
  return (
    <span className="flex flex-col items-center gap-2">
      <span className="flex items-end px-2 pt-1">
        {pins.map((p, i) => (
          <motion.span
            key={i}
            variants={item}
            style={{ rotate: p.rotate, zIndex: i === 1 ? 2 : 1 }}
            className={cn(i !== 1 && '-mx-2 mb-1')}
          >
            <Crop
              src="/feed.webp"
              x={p.x}
              y={p.y}
              w={322}
              size={i === 1 ? 64 : 54}
              h={i === 1 ? 82 : 68}
              className="shadow-[0_6px_14px_-6px_rgb(15_40_80/0.45)] ring-2 ring-paper"
            />
          </motion.span>
        ))}
      </span>
      <motion.span variants={item} className="flex items-center gap-1.5 pb-0.5 font-medium">
        <span className="grid size-4 place-items-center rounded-full bg-mint text-paper">
          <CheckIcon className="size-2.5" />
        </span>
        Saved to Inspo
      </motion.span>
    </span>
  )
}

/** Colour search: "blue" in a search field, matching pins pop in. */
function ColourPeek() {
  const hits = [
    { x: 28, y: 277 },
    { x: 369, y: 528 },
    { x: 28, y: 783 },
    { x: 28, y: 1176 },
  ]
  return (
    <span className="flex w-[7.5rem] flex-col gap-2">
      <motion.span
        variants={item}
        className="flex h-7 items-center gap-1.5 rounded-full bg-paper/80 px-2.5 shadow-[0_0_0_1px_rgb(15_40_80/0.06)]"
      >
        <SearchIcon className="size-3 text-ink-soft" />
        blue
      </motion.span>
      <span className="grid grid-cols-2 gap-1.5">
        {hits.map((h, i) => (
          <motion.span key={i} variants={item}>
            <Crop src="/search.webp" x={h.x} y={h.y} w={322} size={57} />
          </motion.span>
        ))}
      </span>
    </span>
  )
}

/** Bucket: a backup runs to completion, then confirms. */
function BucketPeek() {
  return (
    <span className="flex w-[11rem] items-center gap-2.5 px-0.5 py-0.5">
      <motion.span
        variants={item}
        className="grid size-9 shrink-0 place-items-center rounded-full bg-[linear-gradient(160deg,#5db6f3,#1a74c9)] text-paper shadow-[inset_0_1px_0_rgb(255_255_255/0.4)]"
      >
        <CloudIcon className="size-4.5" />
      </motion.span>
      <span className="flex min-w-0 flex-1 flex-col gap-1.5">
        <motion.span variants={item} className="flex items-center justify-between font-medium">
          Backed up
          <motion.span
            initial={{ scale: 0, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            transition={{ ...spring, delay: 1.05 }}
            className="grid size-4 place-items-center rounded-full bg-mint text-paper"
          >
            <CheckIcon className="size-2.5" />
          </motion.span>
        </motion.span>
        <motion.span variants={item} className="block h-1.5 overflow-hidden rounded-full bg-ink/10">
          <motion.span
            className="block h-full origin-left rounded-full bg-[linear-gradient(90deg,#5db6f3,#1a74c9)]"
            initial={{ scaleX: 0 }}
            animate={{ scaleX: 1 }}
            transition={{ duration: 0.9, delay: 0.15, ease: [0.65, 0, 0.35, 1] }}
          />
        </motion.span>
        <motion.span variants={item} className="text-ink-soft tabular-nums">
          44 pins · 312 MB
        </motion.span>
      </span>
    </span>
  )
}
