import type { AnchorHTMLAttributes } from 'react'
import { cn } from '#/lib/cn'

type Props = AnchorHTMLAttributes<HTMLAnchorElement> & {
  variant?: 'solid' | 'glass'
}

/** Link styled as a button. One size, site-wide: 40px tall, small label. */
export function ButtonLink({ variant = 'solid', className, children, ...props }: Props) {
  return (
    <a
      className={cn(
        'inline-flex h-10 items-center gap-2 rounded-full px-4 text-small font-medium whitespace-nowrap select-none',
        'transition-[transform,background-color] duration-150 ease-out active:scale-[0.96]',
        variant === 'solid' && 'bg-ink text-paper hover:bg-ink/85',
        variant === 'glass' && 'glass text-ink hover:bg-paper/60',
        className,
      )}
      {...props}
    >
      {children}
    </a>
  )
}
