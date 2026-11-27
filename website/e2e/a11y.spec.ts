import AxeBuilder from '@axe-core/playwright'
import { expect, open, scrollThrough, scrollToY, test } from './fixtures'

const WCAG = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice']

type Page = ConstructorParameters<typeof AxeBuilder>[0]['page']

/** WebKit only tabs to links when a browser preference allows it, so keyboard order is checked in Chromium and Firefox. */
const KEYBOARD_SKIP = 'Keyboard order is checked on desktop Chromium and Firefox.'

/** Waits for one-off entrance animations (not looping or scroll-driven ones) so colours are final. */
async function settle(page: Page) {
  await page.evaluate(() =>
    Promise.all(
      document
        .getAnimations()
        .filter((a) => a.timeline === document.timeline && a.effect?.getTiming().iterations !== Infinity)
        .map((a) => a.finished.catch(() => undefined)),
    ),
  )
}

/** Axe scan of the current page state; fails on any violation and prints each one. */
async function audit(page: Page, exclude: string[] = []) {
  await settle(page)
  const builder = new AxeBuilder({ page }).withTags(WCAG)
  for (const selector of exclude) builder.exclude(selector)
  const { violations } = await builder.analyze()
  const report = violations.map(
    (v) => `${v.id} (${v.impact}): ${v.help}\n  ${v.nodes.map((n) => n.target.join(' ')).join('\n  ')}`,
  )
  expect(report, 'accessibility violations').toEqual([])
}

test.describe('accessibility', { tag: '@a11y' }, () => {
  test('the privacy page has no WCAG 2.2 AA violations', async ({ page }) => {
    await page.goto('/privacy')
    await page.locator('html[data-hydrated]').waitFor({ state: 'attached' })
    await audit(page)
  })

  test('the TNC page has no WCAG 2.2 AA violations', async ({ page }) => {
    await page.goto('/tnc')
    await page.locator('html[data-hydrated]').waitFor({ state: 'attached' })
    await audit(page)
  })

  test('the landing page at the top has no WCAG 2.2 AA violations', async ({ page }) => {
    await open(page)
    await audit(page)
  })

  test('the docked nav and the rest of the page have no violations', async ({ page }) => {
    await open(page)
    await scrollThrough(page)
    await scrollToY(page, 600)
    await expect(page.locator('[data-docked]')).toHaveCount(1)
    // The hero copy deliberately fades as it scrolls away; the test above audits it at full strength.
    await audit(page, ['.px-content'])
  })

  test('every link can be reached with the keyboard, in order', async ({ page, isMobile, browserName }) => {
    test.skip(isMobile || browserName === 'webkit', KEYBOARD_SKIP)
    await open(page)
    const links = await page.locator('a[href]:visible').count()
    const order: number[] = []
    for (let i = 0; i < links; i++) {
      await page.keyboard.press('Tab')
      // Position among all links, so two links with the same text still count separately.
      order.push(await page.evaluate(() => [...document.querySelectorAll('a[href]')].indexOf(document.activeElement!)))
    }
    expect(new Set(order).size).toBe(links)
    expect(order).toEqual([...order].sort((a, b) => a - b))
  })

  test('focus is always visible', async ({ page, isMobile, browserName }) => {
    test.skip(isMobile || browserName === 'webkit', KEYBOARD_SKIP)
    await open(page)
    for (let i = 0; i < 6; i++) {
      await page.keyboard.press('Tab')
      const visible = await page.evaluate(() => {
        const s = getComputedStyle(document.activeElement!)
        return s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0
      })
      expect(visible).toBe(true)
    }
  })
})
