import { HeadContent, Scripts, createRootRoute } from '@tanstack/react-router'
import { useEffect } from 'react'

import { SITE_URL } from '#/lib/links'
import { useSmoothScroll } from '#/lib/scroll'
import appCss from '../styles.css?url'

const TITLE = 'Portland — Self-hosted Pinterest alternative for Android'
const DESCRIPTION =
  'Save images and videos into boards on Android. Fast, fluid, open source, and synced to your own Cloudflare R2 bucket.'
const PAGE_URL = `${SITE_URL}/`
// Social crawlers (X, LinkedIn, WhatsApp, Slack…) need an absolute image URL; 1200×630 JPEG under 100 KB.
const IMAGE = `${SITE_URL}/og.jpg`
const IMAGE_ALT = 'Portland: the app’s pin feed on a phone rising out of clouds, with “Save what inspires you.”'

export const Route = createRootRoute({
  head: () => ({
    meta: [
      { charSet: 'utf-8' },
      { name: 'viewport', content: 'width=device-width, initial-scale=1, viewport-fit=cover' },
      { title: TITLE },
      { name: 'description', content: DESCRIPTION },
      { name: 'theme-color', content: '#1a74c9' },
      { property: 'og:type', content: 'website' },
      { property: 'og:site_name', content: 'Portland' },
      { property: 'og:locale', content: 'en_US' },
      { property: 'og:url', content: PAGE_URL },
      { property: 'og:title', content: TITLE },
      { property: 'og:description', content: DESCRIPTION },
      { property: 'og:image', content: IMAGE },
      { property: 'og:image:type', content: 'image/jpeg' },
      { property: 'og:image:width', content: '1200' },
      { property: 'og:image:height', content: '630' },
      { property: 'og:image:alt', content: IMAGE_ALT },
      { name: 'twitter:card', content: 'summary_large_image' },
      { name: 'twitter:title', content: TITLE },
      { name: 'twitter:description', content: DESCRIPTION },
      { name: 'twitter:image', content: IMAGE },
      { name: 'twitter:image:alt', content: IMAGE_ALT },
    ],
    links: [
      { rel: 'stylesheet', href: appCss },
      { rel: 'canonical', href: PAGE_URL },
      { rel: 'icon', href: '/favicon.ico', sizes: '16x16 32x32 48x48' },
      { rel: 'icon', type: 'image/png', sizes: '192x192', href: '/icon-192.png' },
      { rel: 'apple-touch-icon', sizes: '180x180', href: '/apple-touch-icon.png' },
      { rel: 'manifest', href: '/site.webmanifest' },
      { rel: 'preload', as: 'image', href: '/feed.webp' },
    ],
  }),
  shellComponent: RootDocument,
})

function RootDocument({ children }: { children: React.ReactNode }) {
  useSmoothScroll()
  // Marks the page interactive, so end-to-end tests act only after hydration.
  useEffect(() => document.documentElement.setAttribute('data-hydrated', ''), [])
  return (
    <html lang="en">
      <head>
        <HeadContent />
      </head>
      <body>
        {children}
        <Scripts />
      </body>
    </html>
  )
}
