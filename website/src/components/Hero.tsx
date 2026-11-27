import { ButtonLink } from '#/components/Button'
import { Cloud } from '#/components/Cloud'
import { SavedChip, SearchChip, SyncChip } from '#/components/GlassChip'
import { AndroidIcon, GitHubIcon } from '#/components/icons'
import { Phone } from '#/components/Phone'
import { useHeroParallax, useHeroPointer } from '#/lib/scroll'
import { useOffscreenPause } from '#/lib/useOffscreen'
import { cn } from '#/lib/cn'
import { DOWNLOAD_URL, REPO_URL } from '#/lib/links'

export function Hero() {
  const ref = useOffscreenPause<HTMLElement>()
  useHeroParallax(ref)
  useHeroPointer(ref)
  return (
    <section
      ref={ref}
      aria-labelledby="hero-title"
      className="hero-stage relative isolate overflow-x-clip bg-[linear-gradient(180deg,#1a74c9_0%,#2c87da_26%,#5db6f3_46%,#a9d8f8_64%,#e4f3fd_80%,#ffffff_96%)]"
    >
      <Sky />

      <div className="relative z-10 mx-auto flex max-w-5xl flex-col items-center px-4 pt-32 text-center sm:px-6 sm:pt-[clamp(8rem,14svh,11rem)]">
        <div className="px-content flex flex-col items-center">
          <h1
            id="hero-title"
            className="animate-rise text-display font-medium text-paper [text-shadow:0_1px_24px_rgb(10_50_110/0.25)]"
          >
            <span className="block">Save what inspires you.</span>
            <span className="block">
              Keep it{' '}
              <img
                src="/hero/obj-pin.webp"
                alt=""
                aria-hidden
                width={160}
                height={173}
                decoding="async"
                className="inline-block size-[1.1em] -translate-y-[0.06em] object-contain align-middle"
              />{' '}
              yours.
            </span>
          </h1>

          <p className="mt-5 max-w-sm animate-rise text-body text-balance text-paper/95 [animation-delay:100ms] [text-shadow:0_1px_12px_rgb(10_50_110/0.35)]">
            A self-hosted Pinterest alternative for Android. Fluid as glass, synced to your own R2 bucket.
          </p>

          <div className="mt-8 flex animate-rise flex-wrap items-center justify-center gap-2.5 [animation-delay:180ms]">
            <ButtonLink href={DOWNLOAD_URL} target="_blank" rel="noreferrer">
              <AndroidIcon className="size-4" />
              Download
            </ButtonLink>
            <ButtonLink href={REPO_URL} target="_blank" rel="noreferrer" variant="glass">
              <GitHubIcon className="size-4" />
              View code
            </ButtonLink>
          </div>
        </div>

        <PhoneStage />
      </div>

      <BottomClouds />
    </section>
  )
}

/** Painted sky photo over the CSS gradient; the gradient alone still reads as sky. */
function Sky() {
  return (
    <div aria-hidden className="pointer-events-none absolute inset-0 -z-10 overflow-hidden">
      {/* Slightly oversized so pointer drift and the scroll push never expose its edges. */}
      <img
        src="/hero/sky.webp"
        srcSet="/hero/sky-sm.webp 1280w, /hero/sky.webp 2560w"
        sizes="100vw"
        alt=""
        width={2560}
        height={1440}
        fetchPriority="high"
        decoding="async"
        className="px-sky absolute -inset-x-6 top-0 h-[78%] w-[calc(100%+3rem)] max-w-none object-cover object-top"
      />
      {/* Keeps the headline legible over any sky photo. */}
      <div className="absolute inset-x-0 top-0 h-[42%] bg-[linear-gradient(180deg,rgb(20_100_190/0.45),transparent)]" />
    </div>
  )
}

function PhoneStage() {
  return (
    // Parallax and the entrance live on separate elements so their animations never collide.
    <div className="px-phone mt-12 sm:mt-14">
      <div className="relative animate-rise [animation-delay:260ms]">
        {/*
          Clipped so only the top of the phone rises out of the cloud bank; side padding keeps its
          shadow. On wide screens it grows with the stage (--pw, styles.css), like the clouds.
        */}
        <div className="box-content h-[440px] w-[248px] overflow-hidden [mask-image:linear-gradient(180deg,#000_62%,transparent_92%)] px-16 sm:h-[calc(var(--pw)*1.93)] sm:w-(--pw)">
          <Phone src="/feed.webp" alt="Portland’s masonry feed of saved pins, with Pins and Boards tabs" priority />
        </div>

        {/*
          Chips sit on the phone's own box. Each one overlaps the phone edge by the same 2.5rem
          (desktop) and they step down at an even rhythm, alternating left, right, left. On wide
          screens the steps scale with the phone.
        */}
        <div aria-hidden className="absolute inset-x-16 inset-y-0">
          <SavedChip className={cn(chipLeft, 'top-12 [animation-delay:520ms] sm:top-[calc(var(--pw)*0.286)]')} />
          <SyncChip className={cn(chipRight, 'top-[7.5rem] [animation-delay:620ms] sm:top-[calc(var(--pw)*0.6)]')} />
          <SearchChip className={cn(chipLeft, 'top-[12rem] [animation-delay:720ms] sm:top-[calc(var(--pw)*0.914)]')} />
        </div>
      </div>
    </div>
  )
}

const chipLeft = 'animate-rise -left-12 sm:left-auto sm:right-[calc(100%-2.5rem)]'
const chipRight = 'animate-rise -right-12 sm:right-auto sm:left-[calc(100%-2.5rem)]'

/**
 * The cloud bank that carries the hero into the features section.
 *
 * It hangs past the hero's bottom edge and ends in solid white fog, the same white as the
 * page below, so there is no seam: the sky simply turns into cloud, then into page. Every
 * cloud's flat underside is faded out, and two fog layers move with the clouds in front of
 * them, so whatever sinks into the bank (the phone) never shows underneath it.
 *
 * On wide screens every cloud is placed from the centre in stage units (Cloud.tsx), so the
 * bank keeps one shape from a laptop to an ultra-wide monitor; the flank clouds only show
 * where the screen is wide enough to reach them.
 *
 * Depth, slow to fast (see useHeroParallax):
 *   far   – small hazy clouds high in the sky
 *   back  – a full row across the width, closing every gap
 *   front – the big centre cloud the phone sinks into, with fog under it
 *   near  – low clouds along the seam, with the white floor under them
 */
const fog = 'bg-[linear-gradient(180deg,rgb(255_255_255/0)_0%,rgb(255_255_255/0.8)_14%,#fff_24%)]'

function BottomClouds() {
  return (
    <div
      aria-hidden
      className="pointer-events-none absolute inset-x-0 -bottom-[18rem] z-20 h-[38rem] overflow-x-clip contain-layout sm:-bottom-[calc(var(--u)*288)] sm:h-[calc(var(--u)*704)]"
    >
      <div className="px-cloud-far absolute inset-0 opacity-75">
        <Cloud from="left" narrow="bottom-[25rem] -left-[20%] w-[80%]" wide={{ x: -476, b: 496, w: 403 }} />
        <Cloud from="right" narrow="-right-[22%] bottom-[24rem] w-[80%]" wide={{ x: 490, b: 480, w: 432 }} flip />
        <Cloud from="left" tier={1} wide={{ x: -1010, b: 470, w: 420 }} flip />
        <Cloud from="right" tier={1} wide={{ x: 1030, b: 488, w: 440 }} />
      </div>
      <div className="px-cloud-back absolute inset-0">
        <Cloud from="left" narrow="bottom-[19rem] -left-[60%] w-[130%]" wide={{ x: -605, b: 384, w: 634 }} />
        <Cloud from="right" narrow="-right-[60%] bottom-[18.5rem] w-[130%]" wide={{ x: 605, b: 376, w: 634 }} flip />
        <Cloud from="left" wide={{ x: -230, b: 400, w: 490 }} flip />
        <Cloud from="right" wide={{ x: 245, b: 392, w: 490 }} />
        <Cloud from="left" tier={1} wide={{ x: -1190, b: 372, w: 650 }} flip />
        <Cloud from="right" tier={1} wide={{ x: 1200, b: 380, w: 650 }} />
        <Cloud from="left" tier={2} wide={{ x: -1720, b: 380, w: 650 }} />
        <Cloud from="right" tier={2} wide={{ x: 1730, b: 372, w: 650 }} flip />
      </div>
      <div className="px-cloud-front absolute inset-0">
        <Cloud
          from="below"
          narrow="bottom-[15rem] left-1/2 w-[170%] -translate-x-1/2"
          wide={{ x: 0, b: 288, w: 922 }}
        />
        <Cloud from="left" tier={1} wide={{ x: -1020, b: 276, w: 820 }} flip />
        <Cloud from="right" tier={1} wide={{ x: 1030, b: 282, w: 820 }} />
        <Cloud from="left" tier={2} wide={{ x: -1720, b: 284, w: 820 }} />
        <Cloud from="right" tier={2} wide={{ x: 1730, b: 274, w: 820 }} flip />
        <div
          className={cn(
            'absolute inset-x-0 -bottom-24 h-[32rem] sm:-bottom-[calc(var(--u)*96)] sm:h-[calc(var(--u)*592)]',
            fog,
          )}
        />
      </div>
      <div className="px-cloud-near absolute inset-0">
        <Cloud from="left" narrow="bottom-[11rem] -left-[50%] w-[120%]" wide={{ x: -533, b: 208, w: 662 }} />
        <Cloud from="right" narrow="-right-[50%] bottom-[10.5rem] w-[120%]" wide={{ x: 533, b: 200, w: 662 }} flip />
        <Cloud
          from="below"
          narrow="bottom-[9.5rem] left-1/2 w-[140%] -translate-x-1/2"
          wide={{ x: 0, b: 176, w: 749 }}
          flip
        />
        <Cloud from="left" tier={1} wide={{ x: -1110, b: 236, w: 690 }} flip />
        <Cloud from="right" tier={1} wide={{ x: 1120, b: 244, w: 690 }} />
        <Cloud from="left" tier={2} wide={{ x: -1640, b: 240, w: 690 }} />
        <Cloud from="right" tier={2} wide={{ x: 1650, b: 232, w: 690 }} flip />
        <div
          className={cn(
            'absolute inset-x-0 -bottom-32 h-[29rem] sm:-bottom-[calc(var(--u)*128)] sm:h-[calc(var(--u)*528)]',
            fog,
          )}
        />
      </div>
    </div>
  )
}
