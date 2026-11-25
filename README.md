# Portland

A private, single-user inspiration safe for Android. Save images and videos, sort them into boards, and find them again by note or AI description. No accounts, no login.

## Features

- Masonry feed of everything you've saved, newest first
- Add from the system photo picker, or share images and videos to Portland from any app
- Boards, notes, search (notes, AI descriptions, board names)
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

## How it works

- `data/Store.kt`: the whole library is one in-memory `StateFlow`, persisted atomically as `library.json`; media and 560px WebP thumbnails sit in app-private storage.
- `data/Sync.kt`: a WorkManager job uploads pending media, purges deleted objects, then merges `index.json` with R2 (last write wins, deletions win).
- `data/R2.kt`: a hand-written SigV4 OkHttp interceptor, so Coil and ExoPlayer stream straight from the private bucket.
- `data/Captions.kt`: sends the thumbnail to `@cf/mistralai/mistral-small-3.1-24b-instruct`, four at a time. It was the fastest and most concrete of the vision models benchmarked.
- UI: Jetpack Compose, Geist, three type sizes (28/16/13sp), a monochrome alpha-ink palette, light and dark.
