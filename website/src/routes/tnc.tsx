import { createFileRoute } from '@tanstack/react-router'
import { TncPage } from '#/components/LegalPage'
import { pageHead, TNC_PAGE } from '#/lib/seo'

export const Route = createFileRoute('/tnc')({
  head: () => pageHead(TNC_PAGE),
  component: TncPage,
})
