import { createFileRoute } from '@tanstack/react-router'
import { PrivacyPage } from '#/components/LegalPage'
import { pageHead, PRIVACY_PAGE } from '#/lib/seo'

export const Route = createFileRoute('/privacy')({
  head: () => pageHead(PRIVACY_PAGE),
  component: PrivacyPage,
})
