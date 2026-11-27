import { useId, type SVGProps } from 'react'

type IconProps = SVGProps<SVGSVGElement>

const base = { 'aria-hidden': true, focusable: false } as const

export function GitHubIcon(props: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" {...base} {...props}>
      <path d="M12 .3a12 12 0 0 0-3.8 23.38c.6.12.83-.26.83-.57l-.02-2.04c-3.34.72-4.04-1.61-4.04-1.61-.55-1.39-1.33-1.76-1.33-1.76-1.09-.74.08-.73.08-.73 1.2.09 1.84 1.24 1.84 1.24 1.07 1.83 2.8 1.3 3.49 1 .1-.78.42-1.3.76-1.6-2.67-.31-5.47-1.34-5.47-5.94 0-1.31.47-2.38 1.24-3.22-.14-.3-.54-1.52.1-3.18 0 0 1-.32 3.3 1.23a11.5 11.5 0 0 1 6 0c2.28-1.55 3.29-1.23 3.29-1.23.64 1.66.24 2.88.12 3.18a4.65 4.65 0 0 1 1.23 3.22c0 4.61-2.8 5.63-5.48 5.92.42.36.81 1.1.81 2.22l-.01 3.29c0 .31.21.69.82.57A12 12 0 0 0 12 .3" />
    </svg>
  )
}

/** Android robot head, drawn from primitives. */
export function AndroidIcon(props: IconProps) {
  const maskId = useId()
  return (
    <svg viewBox="0 0 24 24" fill="none" {...base} {...props}>
      <mask id={maskId}>
        <rect width="24" height="24" fill="#fff" />
        <circle cx="8" cy="14.6" r="1.05" fill="#000" />
        <circle cx="16" cy="14.6" r="1.05" fill="#000" />
      </mask>
      <path d="M1.5 19.5a10.5 10.5 0 0 1 21 0z" fill="currentColor" mask={`url(#${maskId})`} />
      <path d="M6.6 10.4 4.6 6.9M17.4 10.4l2-3.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  )
}

export function CheckIcon(props: IconProps) {
  return (
    <svg viewBox="0 0 16 16" fill="none" {...base} {...props}>
      <path d="m3.5 8.5 3 3 6-7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

export function CloudIcon(props: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" {...base} {...props}>
      <path
        d="M7 18.5h10.25a4.25 4.25 0 0 0 .6-8.46A6 6 0 0 0 6.3 11.2 3.65 3.65 0 0 0 7 18.5Z"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinejoin="round"
      />
      <path
        d="m9.5 14 2 2 3.5-4"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

const stroke = { stroke: 'currentColor', strokeWidth: 1.7, strokeLinecap: 'round', strokeLinejoin: 'round' } as const

export function ShareIcon(props: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" {...base} {...props}>
      <path d="M12 15V4m0 0L8 8m4-4 4 4" {...stroke} />
      <path d="M7 11H6a2 2 0 0 0-2 2v5a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-5a2 2 0 0 0-2-2h-1" {...stroke} />
    </svg>
  )
}

export function LinkIcon(props: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" {...base} {...props}>
      <path d="M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1" {...stroke} />
      <path d="M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1" {...stroke} />
    </svg>
  )
}

export function PhotoIcon(props: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" {...base} {...props}>
      <rect x="3.5" y="4.5" width="17" height="15" rx="3" {...stroke} />
      <circle cx="9" cy="9.5" r="1.6" {...stroke} />
      <path d="m4 17 4.5-4.5 3.5 3.5 2.5-2.5L20 19" {...stroke} />
    </svg>
  )
}

export function SearchIcon(props: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" {...base} {...props}>
      <circle cx="11" cy="11" r="6.5" stroke="currentColor" strokeWidth="1.8" />
      <path d="m16 16 4 4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  )
}
