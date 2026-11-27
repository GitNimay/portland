import { useRef } from 'react'
import { DOWNLOAD_URL, REPO_URL } from '#/lib/links'
import { useFooterMotion } from '#/lib/scroll'

const columns = [
  {
    title: 'Product',
    links: [
      { label: 'Features', href: '#features' },
      { label: 'Download', href: DOWNLOAD_URL, external: true },
      { label: 'Releases', href: `${REPO_URL}/releases`, external: true },
    ],
  },
  {
    title: 'Open source',
    links: [
      { label: 'GitHub', href: REPO_URL, external: true },
      { label: 'Report an issue', href: `${REPO_URL}/issues`, external: true },
    ],
  },
]

/**
 * Quiet footer: brand and links on white, then the page fades back into the hero's sky,
 * where a large soft wordmark rises out of the clouds as you reach the end.
 */
export function Footer() {
  const ref = useRef<HTMLElement>(null)
  useFooterMotion(ref)

  return (
    <footer
      ref={ref}
      className="ft-scope relative isolate overflow-hidden bg-[linear-gradient(180deg,#ffffff_0%,#ffffff_26%,#e4f3fd_40%,#a9d8f8_58%,#5db6f3_82%,#3a9be6_100%)]"
    >
      <div className="mx-auto flex max-w-5xl flex-col gap-12 px-4 pt-8 sm:flex-row sm:justify-between sm:px-6 sm:pt-10">
        <div className="max-w-xs">
          <a href="/" className="inline-flex items-center gap-2 rounded-full text-body font-medium text-ink">
            <span className="grid size-7 place-items-center overflow-hidden rounded-full bg-paper shadow-[0_0_0_1px_rgb(15_40_80/0.08),0_1px_2px_rgb(15_40_80/0.08)]">
              <img
                src="/mark.webp"
                alt=""
                width={144}
                height={144}
                loading="lazy"
                decoding="async"
                className="size-full scale-[1.55] object-contain"
              />
            </span>
            Portland
          </a>
          <p className="mt-3 text-small text-pretty text-ink-soft">
            A self-hosted Pinterest alternative for Android. Your library, your bucket.
          </p>
        </div>

        <nav aria-label="Footer" className="grid grid-cols-2 gap-x-16 gap-y-8">
          {columns.map((col) => (
            <div key={col.title}>
              <h2 className="text-small font-medium text-ink">{col.title}</h2>
              <ul className="mt-3 space-y-2">
                {col.links.map((l) => (
                  <li key={l.label}>
                    <a
                      href={l.href}
                      {...(l.external ? { target: '_blank', rel: 'noreferrer' } : {})}
                      className="text-small text-ink-soft transition-colors duration-150 hover:text-ink"
                    >
                      {l.label}
                    </a>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </nav>
      </div>

      <p className="mx-auto mt-14 max-w-5xl px-4 text-small text-ink-soft tabular-nums sm:px-6">© 2026 Portland</p>

      {/* Wordmark artwork: scales with the viewport like an image, so it sits outside the type scale. */}
      <div aria-hidden className="pointer-events-none relative mt-4 h-[13rem] sm:h-[clamp(9rem,24vw,22rem)]">
        <svg
          viewBox="0 0 1000 230"
          className="ft-mark absolute inset-x-0 bottom-[34%] mx-auto w-[min(100%,72rem)] px-4 sm:bottom-[8%]"
          preserveAspectRatio="xMidYMax meet"
        >
          <defs>
            <linearGradient id="ft-mark-fill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stopColor="#fff" stopOpacity="0.95" />
              <stop offset="1" stopColor="#fff" stopOpacity="0.25" />
            </linearGradient>
          </defs>
          <text
            x="500"
            y="205"
            textAnchor="middle"
            textLength="960"
            lengthAdjust="spacingAndGlyphs"
            fill="url(#ft-mark-fill)"
            style={{ font: '500 250px "Inter Variable", Inter, sans-serif', letterSpacing: '-0.04em' }}
          >
            Portland
          </text>
        </svg>

        {/*
          Cloud bank: four overlapping clouds spanning the full width, sunk below the edge so
          their flat undersides never show, over a soft white base that closes the page.
        */}
        <div className="ft-cloud absolute inset-x-0 bottom-0 h-full">
          <img
            src="/hero/cloud-a.webp"
            alt=""
            width={1120}
            height={403}
            loading="lazy"
            decoding="async"
            className="absolute -bottom-[2%] -left-[22%] w-[72%] max-w-none sm:-bottom-[22%] sm:-left-[14%] sm:w-[54%]"
          />
          <img
            src="/hero/cloud-a.webp"
            alt=""
            width={1120}
            height={403}
            loading="lazy"
            decoding="async"
            className="absolute -right-[22%] -bottom-[4%] w-[72%] max-w-none -scale-x-100 sm:-right-[14%] sm:-bottom-[24%] sm:w-[54%]"
          />
          <img
            src="/hero/cloud-a.webp"
            alt=""
            width={1120}
            height={403}
            loading="lazy"
            decoding="async"
            className="absolute -bottom-[8%] left-[8%] w-[62%] max-w-none -scale-x-100 sm:-bottom-[30%] sm:left-[16%] sm:w-[46%]"
          />
          <img
            src="/hero/cloud-a.webp"
            alt=""
            width={1120}
            height={403}
            loading="lazy"
            decoding="async"
            className="absolute -bottom-[14%] left-1/2 w-full max-w-none -translate-x-1/2 sm:-bottom-[38%] sm:w-[72%]"
          />
          <div className="absolute inset-x-0 -bottom-px h-[22%] bg-[linear-gradient(180deg,transparent,rgb(255_255_255/0.9)_60%,#ffffff)]" />
        </div>
      </div>
    </footer>
  )
}
