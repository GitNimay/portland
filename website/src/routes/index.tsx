import { createFileRoute } from '@tanstack/react-router'
import { Features } from '#/components/Features'
import { Footer } from '#/components/Footer'
import { Hero } from '#/components/Hero'
import { Nav } from '#/components/Nav'
import { HOME_PAGE, pageHead } from '#/lib/seo'

export const Route = createFileRoute('/')({
  head: () => {
    const head = pageHead(HOME_PAGE)
    return { ...head, links: [...head.links, { rel: 'preload' as const, as: 'image', href: '/feed.webp' }] }
  },
  component: Home,
})

function Home() {
  return (
    <>
      <Nav />
      <main>
        <Hero />
        <Features />
      </main>
      <Footer />
    </>
  )
}
