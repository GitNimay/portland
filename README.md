<img src="docs/logo.png" width="96" alt="Portland logo">

# Portland

A private, single-user inspiration safe for Android. Save images and videos, sort them into boards, and find them again by note or AI description. No accounts, no login.

## Features

- Masonry feed of everything you've saved, newest first
- Tap + and the glass button morphs into a panel: pick from the photo library, or paste a link
- Links (pasted, or shared to Portland as text) download in the background, even with the app closed: direct image/video URLs and pages that advertise one (og:video, og:image). Script-only sites such as YouTube or Instagram are not supported
- Boards, notes, search (notes, AI descriptions, board names, and colours: "blue", "red pink", "grey" or a tap on a swatch)
- Viewer: swipe between pins, pinch-zoom images, looping video
- Every pin is backed up to Cloudflare R2; a fresh install restores the whole library
- Each new pin is described by Workers AI in the background (~1–2s) and the description fades into the feed

## Setup

Credentials live in `local.properties` (git-ignored). Without them the app runs fully offline.

```properties
r2.accountId=<cloudflare account id>
r2.bucket=portland
r2.accessKey=<R2 access key id>
r2.secretKey=<R2 secret access key>
ai.token=<API token with Workers AI read/write>
```

One user API token scoped to *Workers AI Read/Write* plus *R2 Bucket Item Read/Write on the `portland` bucket* covers both: the access key is the token id and the secret key is the SHA-256 of the token value.

## Build

```sh
./gradlew :app:testDebugUnitTest   # JVM tests: SigV4, sync merge, captions
./gradlew :app:assembleRelease     # R8-shrunk APK (~4.5 MB), signed with the debug key for sideloading
```

## Releases and updates

Push to `main` and GitHub Actions (`.github/workflows/android.yml`) tests, builds and signs the release. It then uploads the APK and `releases/latest.json` to R2 and creates a GitHub Release.

The installed app checks `latest.json` every time it opens and every 15 minutes while it stays open. It downloads a newer build in the background and checks its SHA-256, then shows "Version … is ready · Restart" in the header. Tapping it installs the update and reopens Portland. Android 12+ needs no installer prompt for a self-update once Portland may "Install unknown apps" (asked once), but Google Play Protect still asks to scan each new build of a sideloaded app from an unverified developer.

The signing key (`release.jks`, git-ignored) and its password must never be lost or changed: Android only accepts updates signed with the same key. Keep a copy somewhere safe. CI has it as the `RELEASE_KEYSTORE` secret.

## How it works

- `data/Store.kt`: the whole library is one in-memory `StateFlow`, persisted atomically as `library.json`; media and 560px WebP thumbnails sit in app-private storage.
- `data/Sync.kt`: a WorkManager job uploads pending media, purges deleted objects, then merges `index.json` with R2 (last write wins, deletions win). It then lists the bucket and deletes any media or thumbnail no pin uses: deleted pins at once, unknown objects after 24 hours (another install may be mid-upload). It never sweeps unless the remote index was read, so R2 holds exactly the live library.
- `data/Colors.kt`: colour search without AI. Each thumbnail is bucketed pixel by pixel (HSV) into 12 named colours; names covering enough of the image (5% for colours, 20% for black/white/gray) are stored on the pin and synced in the index.
- `data/Links.kt`: a WorkManager job per link, so downloads survive the app closing and retry on network errors.
- `data/R2.kt`: a hand-written SigV4 OkHttp interceptor, so Coil and ExoPlayer stream straight from the private bucket.
- `data/Captions.kt`: sends the thumbnail to `@cf/mistralai/mistral-small-3.1-24b-instruct`, four at a time. It was the fastest and most concrete of the vision models benchmarked.
- UI: Jetpack Compose, Geist, three type sizes (28/16/13sp), a monochrome alpha-ink palette, light and dark.
