import { expect, open, scrollThrough, scrollToY, test } from './fixtures'

const REPO = 'https://github.com/GitNimay/portland'

test.beforeEach(async ({ page }) => {
  await open(page)
})

test.describe('document', () => {
  test('has a title, description and language', async ({ page }) => {
    await expect(page).toHaveTitle(/^Portland — /)
    await expect(page.locator('meta[name="description"]')).toHaveAttribute('content', /Android/)
    await expect(page.locator('html')).toHaveAttribute('lang', 'en')
  })

  test('is server-rendered, so content is in the HTML before any script runs', async ({ request }) => {
    const html = await (await request.get('/')).text()
    expect(html).toContain('Save what inspires you.')
    expect(html).toContain('id="features"')
    expect(html).toContain('<footer')
  })

  test('has exactly one h1 and a logical heading order', async ({ page }) => {
    await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1)
    const levels = await page.locator('h1, h2, h3, h4, h5, h6').evaluateAll((hs) => hs.map((h) => Number(h.tagName[1])))
    for (let i = 1; i < levels.length; i++) expect(levels[i] - levels[i - 1]).toBeLessThanOrEqual(1)
  })
})

test.describe('hero', () => {
  test('shows the headline and both calls to action', async ({ page }) => {
    const hero = page.getByRole('region', { name: /save what inspires you/i })
    await expect(hero.getByRole('heading', { level: 1 })).toBeVisible()
    await expect(hero.getByRole('link', { name: 'Download' })).toHaveAttribute('href', `${REPO}/releases/latest`)
    await expect(hero.getByRole('link', { name: 'View code' })).toHaveAttribute('href', REPO)
  })

  test('shows the app screenshot above the fold', async ({ page }) => {
    const shot = page.getByRole('img', { name: /masonry feed/i }).first()
    await expect(shot).toBeVisible()
    await expect.poll(() => shot.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0)
  })
})

test.describe('navigation', () => {
  const shell = '[data-docked]'

  test('starts as a plain row and docks into a pill on scroll', async ({ page }) => {
    await expect(page.locator(shell)).toHaveCount(0)
    await scrollToY(page, 600)
    await expect(page.locator(shell)).toHaveCount(1)
    await scrollToY(page, 0)
    await expect(page.locator(shell)).toHaveCount(0)
  })

  test('stays pinned to the top while scrolling', async ({ page }) => {
    const nav = page.getByRole('navigation', { name: 'Primary' })
    await scrollToY(page, 2000)
    await expect(nav).toBeInViewport()
    const box = await nav.boundingBox()
    expect(box!.y).toBeLessThan(40)
  })

  test('Features jumps to the feature section', async ({ page, isMobile }) => {
    test.skip(isMobile, 'The text links are hidden on phones; only the logo and Download remain.')
    await page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Features' }).click()
    await expect(page.locator('#features')).toBeInViewport()
    await expect(page).toHaveURL(/#features$/)
  })

  test('keeps Download reachable on every screen size', async ({ page }) => {
    const download = page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Download' })
    await expect(download).toBeVisible()
    await scrollToY(page, 1500)
    await expect(download).toBeVisible()
  })
})

test.describe('features', () => {
  test('lists all five feature cards', async ({ page }) => {
    const section = page.locator('#features')
    await section.scrollIntoViewIfNeeded()
    await expect(section.getByRole('heading', { level: 3 })).toHaveText([
      'Fluid as glass',
      'Find anything',
      'Boards that stay tidy',
      'Your bucket, your backups',
      'One tap from any app',
    ])
  })

  test('cards sit still while the page scrolls', async ({ page }) => {
    const card = page.locator('#features article').first()
    await card.scrollIntoViewIfNeeded()
    const before = await card.evaluate((el) => getComputedStyle(el).transform)
    await page.evaluate(() => window.scrollBy(0, 200))
    await page.waitForTimeout(400)
    const after = await card.evaluate((el) => getComputedStyle(el).transform)
    expect(before).toBe('none')
    expect(after).toBe('none')
  })
})

test.describe('footer', () => {
  test('shows the wordmark and links, all opening safely', async ({ page }) => {
    const footer = page.getByRole('contentinfo')
    await footer.scrollIntoViewIfNeeded()
    await expect(footer.locator('svg.ft-mark')).toBeVisible()
    const external = footer.locator('a[href^="http"]')
    await expect(external).toHaveCount(4)
    for (const link of await external.all()) {
      await expect(link).toHaveAttribute('target', '_blank')
      await expect(link).toHaveAttribute('rel', /noreferrer/)
    }
  })
})

test.describe('layout', () => {
  test('never scrolls sideways', async ({ page }) => {
    await scrollThrough(page)
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    )
    expect(overflow).toBeLessThanOrEqual(0)
  })

  test('loads every image on the page', async ({ page }) => {
    await scrollThrough(page)
    const broken = await page
      .locator('img')
      .evaluateAll((imgs) =>
        (imgs as HTMLImageElement[]).filter((img) => img.complete && img.naturalWidth === 0).map((img) => img.src),
      )
    expect(broken).toEqual([])
  })
})

test.describe('reduced motion', () => {
  test.use({ reducedMotion: 'reduce' })

  test('keeps the hero still while scrolling', async ({ page }) => {
    await scrollToY(page, 500)
    await page.waitForTimeout(300)
    const transform = await page.locator('.px-content').evaluate((el) => getComputedStyle(el).transform)
    expect(transform).toBe('none')
  })

  test('still shows everything', async ({ page }) => {
    await scrollThrough(page)
    await expect(page.locator('#features h2')).toBeVisible()
    await expect(page.getByRole('contentinfo')).toBeVisible()
  })
})
