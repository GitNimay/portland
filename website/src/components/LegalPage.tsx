import { REPO_URL } from '#/lib/links'
import { updatedLabel } from '#/lib/seo'
import { Nav } from '#/components/Nav'

type Section = { id: string; title: string; body: React.ReactNode }

function Out({ href, children }: { href: string; children: string }) {
  return (
    <a href={href} target="_blank" rel="noreferrer">
      {children}
    </a>
  )
}

const privacySections: Section[] = [
  {
    id: 'the-app',
    title: 'The app',
    body: 'Portland is a self-hosted, on-device Pinterest alternative for Android, and it is open source. There is no Portland account. Pins, boards, notes and settings stay on the phone unless you choose a bucket of your own.',
  },
  {
    id: 'on-device',
    title: 'On this phone',
    body: 'With no bucket connected, the library does not leave the device. Android backup is turned off. A Cloudflare token, if you add one, is sealed with the Android Keystore and is not part of a backup.',
  },
  {
    id: 'your-bucket',
    title: 'Your bucket',
    body: 'You can connect a Cloudflare R2 bucket you control. Pins can live in that bucket, or stay on the phone with a copy stored there. Portland does not run the bucket and never receives what you put in it.',
  },
  {
    id: 'descriptions',
    title: 'Descriptions',
    body: 'If your token may use Workers AI, a small thumbnail is sent to your own Cloudflare account to write a short caption. That call goes to Cloudflare, not to a Portland server.',
  },
  {
    id: 'links-you-save',
    title: 'Links you save',
    body: 'When you share or paste a link, the app fetches that address so the image or video can be saved. The request goes to the site you chose.',
  },
  {
    id: 'updates',
    title: 'Updates',
    body: 'Release builds ask GitHub for the latest public release, then download the APK and check its checksum. The request does not include your library.',
  },
  {
    id: 'this-website',
    title: 'This website',
    body: 'This site is static. It does not use analytics, advertising, accounts or cookies. The host may keep an ordinary request log, such as an IP address and the page you opened, in order to serve it.',
  },
  {
    id: 'changes',
    title: 'Changes',
    body: 'This policy describes the app and site in the public repository. When that behavior changes, this page changes with it.',
  },
]

const tncSections: Section[] = [
  {
    id: 'license',
    title: 'License',
    body: (
      <>
        The app and the source of this website are under the MIT license. Copyright 2026 Nimesh. You may use, copy,
        modify and share them if the copyright and permission notice stays with the copy. The{' '}
        <Out href={`${REPO_URL}/blob/main/LICENSE`}>license file</Out> is in the repository.
      </>
    ),
  },
  {
    id: 'the-app',
    title: 'The app',
    body: 'You install Portland yourself. It runs on your Android device. There is no account with us, and we do not host your library.',
  },
  {
    id: 'your-bucket',
    title: 'Your bucket',
    body: 'Cloudflare R2 is optional. You bring the bucket and the token. Storage, cost and Cloudflare’s rules are between you and Cloudflare.',
  },
  {
    id: 'what-you-save',
    title: 'What you save',
    body: 'Keep only images, videos and pages you have the right to keep. Portland does not grant rights to anyone else’s work.',
  },
  {
    id: 'this-website',
    title: 'This website',
    body: 'This site explains the project and links to the code and releases. It does not sell the app and it does not open an account.',
  },
  {
    id: 'no-warranty',
    title: 'No warranty',
    body: (
      <>
        The software is provided as is, without warranty, as the MIT license states. The authors are not liable for
        damage that comes from using it. Report a vulnerability through{' '}
        <Out href={`${REPO_URL}/security/advisories/new`}>GitHub private advisories</Out>, not in a public issue.
      </>
    ),
  },
]

function Mark({ children }: { children: string }) {
  return <mark className="legal-mark">{children}</mark>
}

export function PrivacyPage() {
  return (
    <LegalPage
      pathname="/privacy"
      kicker="Privacy Policy"
      lead={
        <>
          Your library stays <Mark>on your device</Mark>. This site <Mark>does not collect</Mark> it.
        </>
      }
      sections={privacySections}
    />
  )
}

export function TncPage() {
  return (
    <LegalPage
      pathname="/tnc"
      kicker="TNC"
      lead={
        <>
          Portland is <Mark>free software</Mark>. You run it, and <Mark>you keep what you save</Mark>.
        </>
      }
      sections={tncSections}
    />
  )
}

function LegalPage({
  pathname,
  kicker,
  lead,
  sections,
}: {
  pathname: string
  kicker: string
  lead: React.ReactNode
  sections: Section[]
}) {
  return (
    <div className="legal min-h-svh">
      <Nav tone="paper" pathname={pathname} />
      <main className="mx-auto max-w-5xl px-4 pt-32 pb-20 sm:px-6 sm:pt-40 sm:pb-28">
        <p className="legal-kicker">{kicker}</p>
        <h1 className="legal-lead mt-8 max-w-3xl text-display text-balance sm:mt-12">{lead}</h1>
        <div className="mt-16 grid gap-x-10 gap-y-14 sm:mt-24 sm:grid-cols-2 lg:grid-cols-3 lg:gap-x-16 lg:gap-y-16">
          {sections.map((section) => (
            <section key={section.id} aria-labelledby={section.id}>
              <h2 id={section.id} className="legal-kicker">
                {section.title}
              </h2>
              <p className="legal-copy mt-3 text-body text-pretty">{section.body}</p>
            </section>
          ))}
        </div>
      </main>
      <footer className="mx-auto max-w-5xl px-4 pb-16 sm:px-6 sm:pb-24">
        <p className="text-small">© 2026 Portland · Updated {updatedLabel()}</p>
        <nav aria-label="Related" className="mt-4">
          <ul className="flex flex-wrap gap-x-5 gap-y-2 text-small">
            <li>
              <a href="/">Home</a>
            </li>
            <li>
              <a href="/privacy" aria-current={pathname === '/privacy' ? 'page' : undefined}>
                Privacy
              </a>
            </li>
            <li>
              <a href="/tnc" aria-current={pathname === '/tnc' ? 'page' : undefined}>
                TNC
              </a>
            </li>
            <li>
              <a href="/llms.txt">llms.txt</a>
            </li>
            <li>
              <a href="/sitemap.xml">Sitemap</a>
            </li>
            <li>
              <Out href={REPO_URL}>GitHub</Out>
            </li>
          </ul>
        </nav>
      </footer>
    </div>
  )
}
