import { cn } from '#/lib/cn'
import { CheckIcon, CloudIcon, LinkIcon, PhotoIcon, SearchIcon, ShareIcon } from '#/components/icons'

/**
 * Liquid-glass capsule. One line, an icon tile and at most a word or two.
 * Decorative (aria-hidden): the real content is the page copy beside it.
 */
export function GlassChip({
  className,
  style,
  children,
}: {
  className?: string
  style?: React.CSSProperties
  children: React.ReactNode
}) {
  return (
    <div
      aria-hidden
      style={style}
      className={cn(
        'glass-card absolute z-10 flex h-10 items-center gap-2 rounded-full pr-3.5 pl-1.5 text-small font-medium whitespace-nowrap text-ink',
        className,
      )}
    >
      {children}
    </div>
  )
}

/** Round icon tile at the start of a capsule. */
export function ChipTile({ tone = 'light', children }: { tone?: 'light' | 'sky'; children: React.ReactNode }) {
  return (
    <span
      className={cn(
        'grid size-7 shrink-0 place-items-center rounded-full',
        tone === 'light' &&
          'bg-[linear-gradient(160deg,#ffffff,#e4f3fd)] text-sky-900 shadow-[0_0_0_0.5px_rgb(15_40_80/0.08),0_1px_2px_rgb(15_40_80/0.1)]',
        tone === 'sky' &&
          'bg-[linear-gradient(160deg,#5db6f3,#1a74c9)] text-paper shadow-[inset_0_1px_0_rgb(255_255_255/0.4),0_1px_2px_rgb(15_40_80/0.18)]',
      )}
    >
      {children}
    </span>
  )
}

function Check({ className, style }: { className?: string; style?: React.CSSProperties }) {
  return (
    <span
      style={style}
      className={cn(
        'grid size-5 shrink-0 place-items-center rounded-full bg-mint text-paper shadow-[inset_0_1px_0_rgb(255_255_255/0.35)]',
        className,
      )}
    >
      <CheckIcon className="size-3" />
    </span>
  )
}

type ChipProps = { className?: string; style?: React.CSSProperties }

/** "Saved" with a check that pops once the chip has landed. */
export function SavedChip({ className, style }: ChipProps) {
  return (
    <GlassChip className={className} style={style}>
      <ChipTile>
        <img src="/hero/obj-photos.png" alt="" width={800} height={727} className="size-6 object-contain" />
      </ChipTile>
      Saved
      <Check className="pop-once [animation-delay:1100ms]" />
    </GlassChip>
  )
}

/** Backup in progress: the bar fills, then a check confirms, on a loop. */
export function SyncChip({ className, style }: ChipProps) {
  return (
    <GlassChip className={className} style={style}>
      <ChipTile tone="sky">
        <CloudIcon className="size-4" />
      </ChipTile>
      <span className="h-1.5 w-14 overflow-hidden rounded-full bg-ink/10">
        <span className="loop-fill block size-full rounded-full bg-[linear-gradient(90deg,#5db6f3,#1a74c9)]" />
      </span>
      <span className="tabular-nums">312 MB</span>
      <Check className="loop-check" />
    </GlassChip>
  )
}

const searchSwatches = ['#1a74c9', '#5db6f3', '#1b3b6f', '#a9d8f8']

/** Colour search: a blinking caret, then matching swatches pop in one by one. */
export function SearchChip({ className, style }: ChipProps) {
  return (
    <GlassChip className={className} style={style}>
      <ChipTile>
        <SearchIcon className="size-3.5" />
      </ChipTile>
      <span className="flex items-center">
        blue
        <span className="loop-caret ml-px h-3.5 w-px bg-sky-900" />
      </span>
      <span className="flex -space-x-1">
        {searchSwatches.map((c, i) => (
          <span
            key={c}
            className="loop-swatch size-4 rounded-full ring-2 ring-paper/90"
            style={{ background: c, animationDelay: `${i * 140}ms` }}
          />
        ))}
      </span>
    </GlassChip>
  )
}

/** Pinch the grid: a highlight glides between 2, 3 and 4 columns. No words needed. */
export function ColumnsChip({ className, style }: ChipProps) {
  return (
    <GlassChip className={cn('gap-0 pr-1.5', className)} style={style}>
      <span className="relative grid grid-cols-3">
        <span className="loop-cols absolute inset-y-0 left-0 w-1/3 rounded-full bg-paper shadow-[0_1px_3px_rgb(15_40_80/0.15)]" />
        {[2, 3, 4].map((n) => (
          <span key={n} className="relative grid h-7 w-9 place-items-center">
            <span className="flex gap-[2px]">
              {Array.from({ length: n }, (_, i) => (
                <span key={i} className="h-3.5 w-[3px] rounded-full bg-sky-900/80" />
              ))}
            </span>
          </span>
        ))}
      </span>
    </GlassChip>
  )
}

const sources = [
  { label: 'Post', Icon: ShareIcon },
  { label: 'Link', Icon: LinkIcon },
  { label: 'Photo', Icon: PhotoIcon },
]

/** Three sources, each confirmed in turn, like saves arriving one after another. */
export function SourceChips({ className }: { className?: string }) {
  return (
    <div aria-hidden className={cn('flex flex-col items-center gap-2.5', className)}>
      {sources.map(({ label, Icon }, i) => (
        <GlassChip key={label} className={cn('relative', i === 1 ? 'translate-x-6' : '-translate-x-6')}>
          <ChipTile>
            <Icon className="size-3.5" />
          </ChipTile>
          {label}
          <Check className="loop-check" style={{ animationDelay: `${i * 0.6}s` }} />
        </GlassChip>
      ))}
    </div>
  )
}
