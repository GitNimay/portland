import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { SITE_URL } from '#/lib/links'
import { Route as PrivacyRoute } from '#/routes/privacy'
import { Route as TncRoute } from '#/routes/tnc'
import {
  CONTENT_UPDATED,
  HOME_PAGE,
  PRIVACY_PAGE,
  TNC_PAGE,
  pageGraph,
  pageHead,
  pageUrl,
  updatedLabel,
} from '#/lib/seo'

const publicFile = (name: string) => readFileSync(resolve(process.cwd(), 'public', name), 'utf8')

describe('page URLs', () => {
  it('uses the www canonical host, with a trailing slash only on the homepage', () => {
    expect(pageUrl('/')).toBe(`${SITE_URL}/`)
    expect(pageUrl('/privacy')).toBe(`${SITE_URL}/privacy`)
    expect(pageUrl('/tnc')).toBe(`${SITE_URL}/tnc`)
    expect(SITE_URL).toBe('https://www.portland.n1m35h.in')
  })

  it('formats the updated date in English', () => {
    expect(updatedLabel()).toBe('27 November 2026')
    expect(CONTENT_UPDATED).toBe('2026-11-27')
  })
})

describe('page heads', () => {
  it.each([HOME_PAGE, PRIVACY_PAGE, TNC_PAGE])('keeps $path title and description within search limits', (page) => {
    expect(page.title.length).toBeGreaterThan(0)
    expect(page.title.length).toBeLessThanOrEqual(60)
    expect(page.description.length).toBeGreaterThan(50)
    expect(page.description.length).toBeLessThanOrEqual(160)
    const head = pageHead(page)
    expect(head.meta.find((m) => 'title' in m && m.title)?.title).toBe(page.title)
    expect(head.links).toEqual([{ rel: 'canonical', href: pageUrl(page.path) }])
    const graph = pageGraph(page)['@graph']
    expect(graph.map((node) => node['@type'])).toEqual(['WebSite', 'SoftwareApplication', 'WebPage'])
    expect(graph[2]).toMatchObject({ url: pageUrl(page.path), name: page.title, dateModified: CONTENT_UPDATED })
  })

  it('attaches those heads to the privacy and TNC routes', () => {
    const privacy = PrivacyRoute.options.head!({} as never) as { links: { rel: string; href: string }[] }
    const tnc = TncRoute.options.head!({} as never) as { meta: { title?: string }[] }
    expect(privacy.links[0]).toMatchObject({ rel: 'canonical', href: pageUrl('/privacy') })
    expect(tnc.meta.find((m) => m.title)?.title).toBe(TNC_PAGE.title)
  })

  it('does not call the TNC page by another name', () => {
    expect(TNC_PAGE.title).toBe('TNC — Portland')
    expect(TNC_PAGE.description.toLowerCase()).not.toContain('terms and conditions')
  })
})

describe('crawler files', () => {
  it('allows search and AI crawlers and points at the www sitemap', () => {
    const robots = publicFile('robots.txt')
    expect(robots).toContain('User-agent: *')
    expect(robots).toContain('Allow: /')
    for (const agent of ['Googlebot', 'OAI-SearchBot', 'ChatGPT-User', 'Claude-SearchBot', 'PerplexityBot']) {
      expect(robots).toContain(`User-agent: ${agent}`)
    }
    expect(robots).toContain('Content-Signal: search=yes, ai-input=yes, ai-train=yes')
    expect(robots).toContain('Sitemap: https://www.portland.n1m35h.in/sitemap.xml')
  })

  it('lists canonical pages with lastmod and without changefreq or priority', () => {
    const xml = publicFile('sitemap.xml')
    expect(xml).not.toContain('changefreq')
    expect(xml).not.toContain('priority')
    for (const path of ['/', '/privacy', '/tnc']) {
      expect(xml).toContain(`<loc>${pageUrl(path)}</loc>`)
    }
    expect(xml.match(/<lastmod>2026-11-27<\/lastmod>/g)).toHaveLength(3)
  })

  it('publishes an llms.txt overview and a security.txt contact', () => {
    const llms = publicFile('llms.txt')
    expect(llms.startsWith('# Portland\n')).toBe(true)
    expect(llms).toContain('> A self-hosted, on-device Pinterest alternative for Android.')
    expect(llms).toContain(`${SITE_URL}/privacy`)
    expect(llms).toContain(`${SITE_URL}/tnc`)
    expect(llms).toContain('https://github.com/GitNimay/portland')

    const security = publicFile('security.txt')
    expect(security).toContain('Contact: https://github.com/GitNimay/portland/security/advisories/new')
    expect(security).toContain('Expires: 2027-11-27T23:59:59.000Z')
    expect(security).toContain(`Canonical: ${SITE_URL}/.well-known/security.txt`)
  })
})
