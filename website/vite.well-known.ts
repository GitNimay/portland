import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import type { Connect, Plugin, PreviewServer, ViteDevServer } from 'vite'

/**
 * Serves `/.well-known/security.txt` from the non-hidden `security.txt` asset.
 * Production uses the Vercel rewrite; this covers `vite preview` in CI, where
 * `actions/upload-artifact` strips the `.well-known/` directory from the build.
 */
export function wellKnownSecurity(): Plugin {
  const mount = (server: ViteDevServer | PreviewServer) => {
    const handler: Connect.NextHandleFunction = (req, res, next) => {
      const url = req.url?.split('?')[0]
      if (url !== '/.well-known/security.txt') {
        next()
        return
      }

      const root = server.config.root
      const outDir = path.resolve(root, server.config.build.outDir)
      const file = [path.join(outDir, 'security.txt'), path.join(root, 'public', 'security.txt')].find((candidate) =>
        existsSync(candidate),
      )
      if (!file) {
        next()
        return
      }

      const body = readFileSync(file)
      res.statusCode = 200
      res.setHeader('Content-Type', 'text/plain; charset=utf-8')
      res.setHeader('Content-Length', String(body.byteLength))
      res.end(body)
    }

    // Register before Vite's own middleware so the SPA fallback never wins.
    server.middlewares.use(handler)
  }

  return {
    name: 'well-known-security',
    configureServer: mount,
    configurePreviewServer: mount,
  }
}
