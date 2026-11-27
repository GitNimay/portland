import { Fragment, useRef } from 'react'
import { ColumnsChip, SearchChip, SourceChips, SyncChip } from '#/components/GlassChip'
import { InlinePeek } from '#/components/InlinePeek'
import { Phone } from '#/components/Phone'
import { cn } from '#/lib/cn'
import { useOffscreenPause } from '#/lib/useOffscreen'

const tints = {
  sky: { card: 'bg-[linear-gradient(180deg,#e3f1fd,#f4f9fe_55%,#ffffff)]', glow: 'rgb(255 255 255 / 0.95)' },
  lavender: { card: 'bg-[linear-gradient(180deg,#efecff,#f8f7ff)]', glow: 'rgb(255 255 255 / 0.9)' },
  mint: { card: 'bg-[linear-gradient(180deg,#e8f8ee,#f6fcf8)]', glow: 'rgb(255 255 255 / 0.9)' },
  peach: { card: 'bg-[linear-gradient(180deg,#ffeee9,#fff8f6)]', glow: 'rgb(255 255 255 / 0.9)' },
} as const

export function Features() {
  const ref = useOffscreenPause<HTMLElement>()
  return (
    <section
      ref={ref}
      id="features"
      aria-labelledby="features-title"
      className="relative px-4 pt-16 pb-24 sm:px-6 sm:pt-20 sm:pb-32"
    >
      <Statement />

      {/*
        Alignment rules: every card has the same padding and a two-line description, so the
        visual area starts at the same height in every card of a row. Mockups are centred and
        pinned to the top of that area; phones in the same row share one width. Chips align
        to the card's text column (left-7 = card padding).
      */}
      <ul className="mx-auto mt-16 grid max-w-5xl gap-4 sm:mt-24 md:grid-cols-2 lg:grid-cols-3 lg:grid-rows-[20rem_20rem_21rem]">
        <Card
          tint="sky"
          title="Fluid as glass"
          desc="Native Kotlin, spring gestures and zero-jank scrolling, wrapped in frosted glass."
          className="h-[34rem] md:col-span-2 md:h-[38rem] lg:col-span-2 lg:row-span-2 lg:h-auto"
        >
          <div className="absolute inset-x-0 top-0 flex justify-center gap-4 sm:gap-6">
            <div>
              <Phone src="/feed.webp" alt="Masonry feed of saved pins" className={rowPhone} />
            </div>
            <div>
              <Phone
                src="/viewer.webp"
                alt="Gallery viewer with a filmstrip of pins"
                className={cn(rowPhone, 'delay-75')}
              />
            </div>
          </div>
          <div className="absolute top-24 left-7 z-20 hidden sm:block">
            <ColumnsChip className="relative" />
          </div>
        </Card>

        <Card
          tint="lavender"
          title="Find anything"
          desc="Search notes, boards, twelve colours or AI-written descriptions."
          className="h-[30rem] md:h-[26rem] lg:row-span-2 lg:h-auto"
        >
          <div className="absolute inset-x-0 top-0 flex justify-center">
            <Phone src="/search.webp" alt="Search results for the colour blue" className={rowPhone} />
          </div>
          <div className="absolute top-36 left-7 z-20">
            <SearchChip className="relative" />
          </div>
        </Card>

        <Card
          tint="mint"
          title="Boards that stay tidy"
          desc="Swipe between Pins and Boards. Long-press to batch select."
          className="h-[23rem] md:h-[26rem] lg:h-auto"
        >
          <div className="absolute inset-x-0 top-0 flex justify-center">
            <Phone
              src="/boards.webp"
              alt="Boards view with Inspo and UI Stuffs boards"
              className={cn(phoneHover, 'w-[13rem]')}
            />
          </div>
        </Card>

        <Card
          tint="sky"
          title="Your bucket, your backups"
          desc="Back up to your own Cloudflare R2. Restore on any fresh install."
          className="h-[23rem] md:h-[26rem] lg:h-auto"
        >
          <div className="absolute inset-x-0 top-0 flex flex-col items-center">
            <img
              src="/hero/obj-bucket.png"
              alt=""
              aria-hidden
              width={762}
              height={800}
              loading="lazy"
              className={cn(phoneHover, '-mt-2 w-28')}
            />
            <SyncChip className="relative -mt-8" />
          </div>
        </Card>

        <Card
          tint="peach"
          title="One tap from any app"
          desc="Share a post, paste a link or pick from your gallery."
          className="h-[23rem] md:col-span-2 md:h-[22rem] lg:col-span-1 lg:h-auto"
        >
          <div className="absolute inset-x-0 top-1 flex justify-center">
            <SourceChips />
          </div>
        </Card>
      </ul>
    </section>
  )
}

/** Mockups lift slightly while their card is hovered (fine pointers only). */
const phoneHover = 'transition-[translate] duration-500 ease-out group-hover:-translate-y-2'

/** Phone width shared by every mockup in the first two rows. */
const rowPhone = cn(phoneHover, 'w-[9.5rem] rounded-[34px] p-[7px] sm:w-[13.5rem] [&>div]:rounded-[28px]')

/*
 * The section statement: plain words with small inline objects that open a peek on hover or tap.
 */
const statement: (string | React.ReactNode)[] = [
  'Save anything',
  <InlinePeek key="saved" kind="saved" tilt={-6} />,
  'in a single tap, find it again by colour',
  <InlinePeek key="colour" kind="colour" tilt={3} />,
  'and keep it all in a bucket',
  <InlinePeek key="bucket" kind="bucket" tilt={6} />,
  'you own.',
]

function Statement() {
  return (
    <div className="mx-auto max-w-3xl text-center">
      <h2 id="features-title" className="text-display font-medium text-balance text-ink">
        {statement.map((part, p) => (
          <Fragment key={p}>{part} </Fragment>
        ))}
      </h2>
    </div>
  )
}

type CardProps = {
  tint: keyof typeof tints
  title: string
  desc: string
  className?: string
  children: React.ReactNode
}

function Card({ tint, title, desc, className, children }: CardProps) {
  const light = useRef<HTMLSpanElement>(null)

  // Cursor light: moved with `translate` only (compositor), one write per frame.
  const frame = useRef(0)
  const onPointerMove = (e: React.PointerEvent<HTMLElement>) => {
    if (e.pointerType !== 'mouse') return
    const r = e.currentTarget.getBoundingClientRect()
    const x = e.clientX - r.left
    const y = e.clientY - r.top
    cancelAnimationFrame(frame.current)
    frame.current = requestAnimationFrame(() => {
      if (light.current) light.current.style.translate = `${x}px ${y}px`
    })
  }

  return (
    <li className={className}>
      <article
        onPointerMove={onPointerMove}
        className={cn(
          'group relative grid h-full grid-rows-[auto_1fr] overflow-hidden rounded-[28px]',
          'shadow-[inset_0_1px_0_rgb(255_255_255/0.9),0_0_0_1px_rgb(15_40_80/0.06),0px_2px_3px_-1px_rgba(0,0,0,0.06),0px_1px_0px_0px_rgba(25,28,33,0.02)]',
          tints[tint].card,
        )}
      >
        {/* Soft light pooled behind the mockup, so it reads as lit, not pasted on. */}
        <span
          aria-hidden
          className="pointer-events-none absolute top-24 left-1/2 size-[26rem] -translate-x-1/2 rounded-full"
          style={{ background: `radial-gradient(closest-side, ${tints[tint].glow}, transparent)` }}
        />
        {/* Cursor light: a 20rem radial that follows the pointer while hovering. */}
        <span
          ref={light}
          aria-hidden
          className="pointer-events-none absolute -top-40 -left-40 size-80 rounded-full bg-[radial-gradient(closest-side,rgb(255_255_255/0.75),transparent)] opacity-0 transition-opacity duration-300 group-hover:opacity-100"
        />
        <header className="relative z-20 px-7 pt-7 pb-6">
          <h3 className="text-body font-medium text-ink">{title}</h3>
          {/* Reserved two lines, so every visual area in a row starts at the same height. */}
          <p className="mt-1 min-h-[2lh] max-w-[34ch] text-body text-pretty text-ink-soft">{desc}</p>
        </header>
        <div className="relative min-h-0">{children}</div>
      </article>
    </li>
  )
}
