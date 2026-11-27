import { createFileRoute } from '@tanstack/react-router'
import { Features } from '#/components/Features'
import { Footer } from '#/components/Footer'
import { Hero } from '#/components/Hero'
import { Nav } from '#/components/Nav'

export const Route = createFileRoute('/')({ component: Home })

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
