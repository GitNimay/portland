import { HeadContent, Scripts, createRootRoute } from '@tanstack/react-router'
import { useEffect } from 'react'

import { HOME_DESCRIPTION, HOME_TITLE, OG_IMAGE, OG_IMAGE_ALT, pageUrl } from '#/lib/seo'
import { useSmoothScroll } from '#/lib/scroll'
import appCss from '../styles.css?url'

const PAGE_URL = pageUrl('/')

export const Route = createRootRoute({
  head: () => ({
    meta: [
      { charSet: 'utf-8' },
      { name: 'viewport', content: 'width=device-width, initial-scale=1, viewport-fit=cover' },
      { title: HOME_TITLE },
      { name: 'description', content: HOME_DESCRIPTION },
      { name: 'theme-color', content: '#1a74c9' },
      { property: 'og:type', content: 'website' },
      { property: 'og:site_name', content: 'Portland' },
      { property: 'og:locale', content: 'en_US' },
      { property: 'og:url', content: PAGE_URL },
      { property: 'og:title', content: HOME_TITLE },
      { property: 'og:description', content: HOME_DESCRIPTION },
      { property: 'og:image', content: OG_IMAGE },
      { property: 'og:image:type', content: 'image/jpeg' },
      { property: 'og:image:width', content: '1200' },
      { property: 'og:image:height', content: '630' },
      { property: 'og:image:alt', content: OG_IMAGE_ALT },
      { name: 'twitter:card', content: 'summary_large_image' },
      { name: 'twitter:title', content: HOME_TITLE },
      { name: 'twitter:description', content: HOME_DESCRIPTION },
      { name: 'twitter:image', content: OG_IMAGE },
      { name: 'twitter:image:alt', content: OG_IMAGE_ALT },
    ],
    links: [
      { rel: 'stylesheet', href: appCss },
      { rel: 'icon', href: '/favicon.ico', sizes: '16x16 32x32 48x48' },
      { rel: 'icon', type: 'image/png', sizes: '192x192', href: '/icon-192.png' },
      { rel: 'apple-touch-icon', sizes: '180x180', href: '/apple-touch-icon.png' },
      { rel: 'manifest', href: '/site.webmanifest' },
      { rel: 'alternate', type: 'text/plain', href: '/llms.txt', title: 'LLMs.txt' },
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
