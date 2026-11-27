# Portland website

The landing page for Portland: React 19, TanStack Start (SSR), Tailwind CSS 4, GSAP + Lenis for the hero parallax.

Requires Node 22.12+ (CI uses the version in `.nvmrc`).

```bash
npm install
npm run dev          # http://localhost:3000
```

## Scripts

| Script                  | What it does                                                  |
| ----------------------- | ------------------------------------------------------------- |
| `npm run build`         | Production build into `dist/` (client + SSR server)           |
| `npm run preview`       | Serves the production build                                   |
| `npm run check`         | Format check, lint, typecheck and unit tests, as CI runs them |
| `npm run format`        | Formats everything with Prettier (sorts Tailwind classes)     |
| `npm run lint`          | ESLint: type-aware TypeScript, React Hooks, jsx-a11y          |
| `npm run typecheck`     | `tsc --noEmit`                                                |
| `npm test`              | Vitest unit and component tests (jsdom)                       |
| `npm run test:coverage` | The same, with a coverage report and enforced thresholds      |
| `npm run test:e2e`      | Playwright against the production build (run `build` first)   |

## Tests

- **Unit and component** (`src/**/*.test.ts(x)`): nav docking and lens maths, links, every section's content and semantics, the motion hooks, and the document head.
- **End to end** (`e2e/landing.spec.ts`): the real SSR build in Chromium, Firefox, WebKit, Pixel 7 and iPhone 15. Every test also fails on console errors, page errors and failed requests.
- **Accessibility** (`e2e/a11y.spec.ts`, tagged `@a11y`): axe WCAG 2.2 AA scans at the top and with the nav docked, keyboard order and visible focus.

Run one browser with `npx playwright test --project=webkit`. Install browsers once with `npx playwright install`.

## CI

| Workflow               | Jobs                                                                                                          |
| ---------------------- | ------------------------------------------------------------------------------------------------------------- |
| `website.yml`          | Format, Lint, Typecheck, Unit Tests, Build, E2E (one job per browser), Accessibility, and a `Website CI` gate |
| `website-codeql.yml`   | CodeQL `javascript-typescript` with the `security-extended` queries                                           |
| `website-security.yml` | npm audit and signature verification, Dependency Review (PRs), Trivy, OSV-Scanner                             |

All three run only when `website/` (or the workflow itself) changes. Dependabot updates the npm dependencies weekly in groups, and PRs touching the site get the `area/website` label.
