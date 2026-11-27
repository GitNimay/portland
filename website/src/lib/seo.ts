import { DOWNLOAD_URL, REPO_URL, SITE_URL } from '#/lib/links'

export const CONTENT_UPDATED = '2026-11-27'

export const HOME_TITLE = 'Portland — Self-hosted Pinterest alternative for Android'
export const HOME_DESCRIPTION =
  'Save images and videos into boards on Android. Fast, fluid, open source, and synced to your own Cloudflare R2 bucket.'

export const PRIVACY_TITLE = 'Privacy Policy — Portland'
export const PRIVACY_DESCRIPTION =
  'How Portland keeps your library on your device, what an optional Cloudflare R2 bucket does, and what this website does not collect.'

export const TNC_TITLE = 'TNC — Portland'
export const TNC_DESCRIPTION =
  'The TNC for Portland: an MIT-licensed Android app you run yourself, your own bucket, and this website.'

export const OG_IMAGE = `${SITE_URL}/og.jpg`
export const OG_IMAGE_ALT =
  'Portland: the app’s pin feed on a phone rising out of clouds, with “Save what inspires you.”'

const WEBSITE_ID = `${SITE_URL}/#website`
const APP_ID = `${SITE_URL}/#app`

export type SeoPage = {
  path: '/' | '/privacy' | '/tnc'
  title: string
  description: string
}

export const HOME_PAGE: SeoPage = { path: '/', title: HOME_TITLE, description: HOME_DESCRIPTION }
export const PRIVACY_PAGE: SeoPage = { path: '/privacy', title: PRIVACY_TITLE, description: PRIVACY_DESCRIPTION }
export const TNC_PAGE: SeoPage = { path: '/tnc', title: TNC_TITLE, description: TNC_DESCRIPTION }

/** Absolute canonical URL. The homepage keeps its trailing slash; other paths do not. */
export function pageUrl(path: string) {
  return path === '/' ? `${SITE_URL}/` : `${SITE_URL}${path}`
}

/** English long date for CONTENT_UPDATED, used as the visible “updated” line. */
export function updatedLabel(iso = CONTENT_UPDATED) {
  const year = Number(iso.slice(0, 4))
  const month = Number(iso.slice(5, 7))
  const day = Number(iso.slice(8, 10))
  return new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' }).format(
    new Date(Date.UTC(year, month - 1, day)),
  )
}

/** JSON-LD `@graph` shared by every page: the site, the Android app, and this page. */
export function pageGraph(page: SeoPage) {
  const url = pageUrl(page.path)
  return {
    '@context': 'https://schema.org',
    '@graph': [
      {
        '@type': 'WebSite',
        '@id': WEBSITE_ID,
        name: 'Portland',
        url: `${SITE_URL}/`,
        description: HOME_DESCRIPTION,
        inLanguage: 'en',
      },
      {
        '@type': 'SoftwareApplication',
        '@id': APP_ID,
        name: 'Portland',
        applicationCategory: 'MultimediaApplication',
        operatingSystem: 'Android',
        url: `${SITE_URL}/`,
        downloadUrl: DOWNLOAD_URL,
        codeRepository: REPO_URL,
        license: `${REPO_URL}/blob/main/LICENSE`,
        isAccessibleForFree: true,
        offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD' },
        description: HOME_DESCRIPTION,
      },
      {
        '@type': 'WebPage',
        '@id': `${url}#webpage`,
        url,
        name: page.title,
        description: page.description,
        isPartOf: { '@id': WEBSITE_ID },
        about: { '@id': APP_ID },
        inLanguage: 'en',
        dateModified: CONTENT_UPDATED,
      },
    ],
  }
}

/** Per-page title, description, social tags, canonical URL and JSON-LD. Child routes override the root. */
export function pageHead(page: SeoPage) {
  const url = pageUrl(page.path)
  return {
    meta: [
      { title: page.title },
      { name: 'description', content: page.description },
      { property: 'og:url', content: url },
      { property: 'og:title', content: page.title },
      { property: 'og:description', content: page.description },
      { name: 'twitter:title', content: page.title },
      { name: 'twitter:description', content: page.description },
      { 'script:ld+json': pageGraph(page) },
    ],
    links: [{ rel: 'canonical', href: url }],
  }
}
