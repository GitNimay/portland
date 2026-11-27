import viteReact from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// Unit and component tests. Kept apart from vite.config.ts so the TanStack Start plugin
// (server build, route generation) never runs inside the test environment.
export default defineConfig({
  plugins: [viteReact()],
  resolve: { tsconfigPaths: true },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
    reporters: process.env.CI ? ['default', ['junit', { outputFile: 'test-results/unit-junit.xml' }]] : ['default'],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/routeTree.gen.ts', 'src/router.tsx'],
      reporter: ['text', 'html', 'lcov', 'json-summary'],
      thresholds: { lines: 90, functions: 85, branches: 85, statements: 90 },
    },
  },
})
