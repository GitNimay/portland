import { expect, scrollToY, test } from './fixtures'

const VIEWPORTS = [
  { name: 'narrow phone', width: 320, height: 700 },
  { name: 'mobile', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 800 },
] as const

test.describe('legal pages', () => {
  for (const path of ['/privacy', '/tnc'] as const) {
    test(`${path} is server-rendered with one h1 and no sideways scroll`, async ({ page }) => {
      const html = await (await page.request.get(path)).text()
      expect(html).toContain('<h1')
      expect(html).toContain('rel="canonical"')
      expect(html).toContain('application/ld+json')

      await page.goto(path)
      await page.locator('html[data-hydrated]').waitFor({ state: 'attached' })
      await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1)
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
      )
      expect(overflow).toBeLessThanOrEqual(0)
    })
  }

  test('privacy and TNC are reachable from the navbar', async ({ page }) => {
    await page.goto('/')
    await page.locator('html[data-hydrated]').waitFor({ state: 'attached' })
    const nav = page.getByRole('navigation', { name: 'Primary' })
    await nav.getByRole('link', { name: 'Privacy' }).click()
    await expect(page).toHaveURL(/\/privacy$/)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await nav.getByRole('link', { name: 'TNC' }).click()
    await expect(page).toHaveURL(/\/tnc$/)
    await expect(page.getByRole('heading', { level: 1 })).toContainText(/free software/i)
  })

  test('crawler files are served from the site root', async ({ request }) => {
    const robots = await request.get('/robots.txt')
    expect(robots.status()).toBe(200)
    expect(await robots.text()).toContain('Sitemap: https://www.portland.n1m35h.in/sitemap.xml')

    const sitemap = await request.get('/sitemap.xml')
    expect(sitemap.status()).toBe(200)
    const xml = await sitemap.text()
    expect(xml).toContain('<loc>https://www.portland.n1m35h.in/tnc</loc>')
    expect(xml).not.toContain('<priority>')

    expect((await request.get('/llms.txt')).status()).toBe(200)

    // The file lives at /security.txt so CI artifact upload (which drops hidden dirs) keeps it.
    // Vite preview middleware and the Vercel rewrite both expose the RFC path as well.
    const security = await request.get('/security.txt')
    expect(security.status()).toBe(200)
    const securityBody = await security.text()
    expect(securityBody).toContain('Canonical: https://www.portland.n1m35h.in/.well-known/security.txt')

    const wellKnown = await request.get('/.well-known/security.txt')
    expect(wellKnown.status()).toBe(200)
    expect(await wellKnown.text()).toBe(securityBody)
  })
})

test.describe('responsive layout', () => {
  test.skip(({ browserName }) => browserName !== 'chromium', 'Breakpoints are measured once, in Chromium.')

  for (const viewport of VIEWPORTS) {
    for (const path of ['/', '/privacy', '/tnc'] as const) {
      test(`${path} does not overflow at ${viewport.name}`, async ({ page }) => {
        await page.setViewportSize({ width: viewport.width, height: viewport.height })
        await page.goto(path)
        await page.locator('html[data-hydrated]').waitFor({ state: 'attached' })
        const nav = page.getByRole('navigation', { name: 'Primary' })
        await expect(nav.getByRole('link', { name: 'Privacy' })).toBeVisible()
        await expect(nav.getByRole('link', { name: 'TNC' })).toBeVisible()
        await expect(nav.getByRole('link', { name: 'Download' })).toBeVisible()
        const overflow = await page.evaluate(
          () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
        )
        expect(overflow).toBeLessThanOrEqual(0)

        if (viewport.width >= 1280) {
          await scrollToY(page, 800)
          const scrolled = await page.evaluate(() => window.scrollY)
          if (scrolled > 48) {
            await expect(page.locator('[data-docked]')).toHaveCount(1)
            // The brand and Download slide into the pill; the move takes about 560ms.
            await expect
              .poll(async () => {
                const pill = await page.locator('.nav-glass').boundingBox()
                const brand = await nav.getByRole('link', { name: 'Portland' }).boundingBox()
                return pill && brand ? brand.x - pill.x : -999
              })
              .toBeGreaterThanOrEqual(-1)
            const pill = await page.locator('.nav-glass').boundingBox()
            for (const name of ['Portland', 'Features', 'GitHub', 'Privacy', 'TNC', 'Download']) {
              const box = await nav.getByRole('link', { name }).boundingBox()
              expect(box!.x, name).toBeGreaterThanOrEqual(pill!.x - 1)
              expect(box!.x + box!.width, name).toBeLessThanOrEqual(pill!.x + pill!.width + 1)
            }
          }
        }
      })
    }
  }
})
