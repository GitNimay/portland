import { test as base, expect, type Page } from '@playwright/test'

/**
 * Every test fails if the page logs an error, throws, or a same-origin request fails,
 * so a broken asset or a runtime exception never passes silently.
 */
export const test = base.extend<{ problems: string[] }>({
  problems: [
    async ({ page, baseURL }, use) => {
      const problems: string[] = []
      page.on('console', (msg) => {
        if (msg.type() === 'error') problems.push(`console: ${msg.text()}`)
      })
      page.on('pageerror', (err) => problems.push(`page error: ${err.message}`))
      page.on('requestfailed', (req) => {
        if (req.url().startsWith(baseURL!)) problems.push(`request failed: ${req.url()} (${req.failure()?.errorText})`)
      })
      page.on('response', (res) => {
        if (res.url().startsWith(baseURL!) && res.status() >= 400) problems.push(`HTTP ${res.status()}: ${res.url()}`)
      })
      await use(problems)
      expect(problems, 'page logged errors or failed to load assets').toEqual([])
    },
    { auto: true },
  ],
})

export { expect }

/** Loads the landing page and waits until React has hydrated it. */
export async function open(page: Page) {
  await page.goto('/')
  await page.locator('html[data-hydrated]').waitFor({ state: 'attached' })
}

/** Scrolls to an absolute offset (clamped to the page) and waits until the page is there. */
export async function scrollToY(page: Page, y: number) {
  const target = await page.evaluate((top) => {
    window.scrollTo({ top, behavior: 'instant' })
    return Math.min(top, document.documentElement.scrollHeight - window.innerHeight)
  }, y)
  await expect.poll(() => page.evaluate(() => Math.round(window.scrollY))).toBe(Math.round(target))
}

/** Walk down the whole page so lazy images and scroll effects all get a chance to run. */
export async function scrollThrough(page: Page) {
  const height = await page.evaluate(() => document.documentElement.scrollHeight)
  const step = await page.evaluate(() => window.innerHeight * 0.8)
  for (let y = 0; y < height; y += step) {
    await page.evaluate((top) => window.scrollTo({ top, behavior: 'instant' }), y)
    await page.waitForTimeout(60)
  }
}
