import { cn } from '#/lib/cn'

type Props = {
  src: string
  alt: string
  className?: string
  priority?: boolean
}

/** Android-style frame around a real 720×1616 app screenshot. Width comes from `className`. */
export function Phone({ src, alt, className, priority }: Props) {
  return (
    <div
      className={cn(
        'relative rounded-[46px] bg-[#0e1016] p-[9px] shadow-[0_40px_80px_-30px_rgb(10_40_90/0.55),inset_0_0_0_1.5px_rgb(255_255_255/0.14)]',
        className,
      )}
    >
      <div className="relative overflow-hidden rounded-[37px] bg-paper">
        <img
          src={src}
          alt={alt}
          width={720}
          height={1616}
          loading={priority ? 'eager' : 'lazy'}
          fetchPriority={priority ? 'high' : 'auto'}
          decoding="async"
          className="block h-auto w-full"
        />
        {/* Punch-hole camera */}
        <span
          aria-hidden
          className="absolute top-[11px] left-1/2 size-[11px] -translate-x-1/2 rounded-full bg-[#0e1016]"
        />
      </div>
    </div>
  )
}
