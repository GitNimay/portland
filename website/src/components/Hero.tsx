import { ButtonLink } from '#/components/Button'
import { SavedChip, SearchChip, SyncChip } from '#/components/GlassChip'
import { AndroidIcon, GitHubIcon } from '#/components/icons'
import { Phone } from '#/components/Phone'
import { useHeroParallax } from '#/lib/scroll'
import { useOffscreenPause } from '#/lib/useOffscreen'
import { cn } from '#/lib/cn'
import { DOWNLOAD_URL, REPO_URL } from '#/lib/links'

export function Hero() {
  const ref = useOffscreenPause<HTMLElement>()
  useHeroParallax(ref)
  return (
    <section
      ref={ref}
      aria-labelledby="hero-title"
      className="relative isolate overflow-x-clip bg-[linear-gradient(180deg,#1a74c9_0%,#2c87da_26%,#5db6f3_46%,#a9d8f8_64%,#e4f3fd_80%,#ffffff_96%)]"
    >
      <Sky />

      <div className="relative z-10 mx-auto flex max-w-5xl flex-col items-center px-4 pt-32 text-center sm:px-6 sm:pt-36">
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
    <div aria-hidden className="pointer-events-none absolute inset-0 -z-10">
      <img
        src="/hero/sky.webp"
        srcSet="/hero/sky-sm.webp 1280w, /hero/sky.webp 2560w"
        sizes="100vw"
        alt=""
        width={2560}
        height={1440}
        fetchPriority="high"
        decoding="async"
        className="px-sky absolute inset-x-0 top-0 h-[78%] w-full object-cover object-top"
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
        {/* Clipped so only the top of the phone rises out of the cloud bank; side padding keeps its shadow. */}
        <div className="box-content h-[440px] w-[248px] overflow-hidden [mask-image:linear-gradient(180deg,#000_62%,transparent_92%)] px-16 sm:h-[540px] sm:w-[280px]">
          <Phone src="/feed.webp" alt="Portland’s masonry feed of saved pins, with Pins and Boards tabs" priority />
        </div>

        {/*
          Chips sit on the phone's own box. Each one overlaps the phone edge by the same 2.5rem
          (desktop) and they step down at an even rhythm, alternating left, right, left.
        */}
        <div aria-hidden className="absolute inset-x-16 inset-y-0">
          <SavedChip className={cn(chipLeft, 'top-12 [animation-delay:520ms] sm:top-20')} />
          <SyncChip className={cn(chipRight, 'top-[7.5rem] [animation-delay:620ms] sm:top-[10.5rem]')} />
          <SearchChip className={cn(chipLeft, 'top-[12rem] [animation-delay:720ms] sm:top-[16rem]')} />
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
 * It hangs 18rem past the hero's bottom edge and ends in solid white fog, the same white as
 * the page below, so there is no seam: the sky simply turns into cloud, then into page.
 * Every cloud's flat underside is faded out, and two fog layers move with the clouds in
 * front of them, so whatever sinks into the bank (the phone) never shows underneath it.
 *
 * Depth, slow to fast (see useHeroParallax):
 *   far   – small hazy clouds high in the sky
 *   back  – a full row across the width, closing every gap
 *   front – the big centre cloud the phone sinks into, with fog under it
 *   near  – low clouds along the seam, with the white floor under them
 */
const CLOUD = '/hero/cloud-a.webp'
const fog = 'bg-[linear-gradient(180deg,rgb(255_255_255/0)_0%,rgb(255_255_255/0.8)_14%,#fff_24%)]'

function BottomClouds() {
  return (
    <div
      aria-hidden
      className="pointer-events-none absolute inset-x-0 -bottom-[18rem] z-20 h-[38rem] overflow-x-clip contain-layout sm:h-[44rem]"
    >
      <div className="px-cloud-far absolute inset-0 opacity-75">
        <Cloud className="bottom-[25rem] -left-[20%] w-[80%] sm:bottom-[31rem] sm:left-[3%] sm:w-[28%]" />
        <Cloud className="-right-[22%] bottom-[24rem] w-[80%] -scale-x-100 sm:right-[1%] sm:bottom-[30rem] sm:w-[30%]" />
      </div>
      <div className="px-cloud-back absolute inset-0">
        <Cloud className="bottom-[19rem] -left-[60%] w-[130%] sm:bottom-[24rem] sm:-left-[14%] sm:w-[44%]" />
        <Cloud className="-right-[60%] bottom-[18.5rem] w-[130%] -scale-x-100 sm:-right-[14%] sm:bottom-[23.5rem] sm:w-[44%]" />
        <Cloud className="hidden sm:bottom-[25rem] sm:left-[17%] sm:block sm:w-[34%] sm:-scale-x-100" />
        <Cloud className="hidden sm:right-[16%] sm:bottom-[24.5rem] sm:block sm:w-[34%]" />
      </div>
      <div className="px-cloud-front absolute inset-0">
        <Cloud className="bottom-[15rem] left-1/2 w-[170%] -translate-x-1/2 sm:bottom-[18rem] sm:w-[64%]" />
        <div className={cn('absolute inset-x-0 -bottom-24 h-[32rem] sm:h-[37rem]', fog)} />
      </div>
      <div className="px-cloud-near absolute inset-0">
        <Cloud className="bottom-[11rem] -left-[50%] w-[120%] sm:bottom-[13rem] sm:-left-[10%] sm:w-[46%]" />
        <Cloud className="-right-[50%] bottom-[10.5rem] w-[120%] -scale-x-100 sm:-right-[10%] sm:bottom-[12.5rem] sm:w-[46%]" />
        <Cloud className="bottom-[9.5rem] left-1/2 w-[140%] -translate-x-1/2 -scale-x-100 sm:bottom-[11rem] sm:w-[52%]" />
        <div className={cn('absolute inset-x-0 -bottom-32 h-[29rem] sm:h-[33rem]', fog)} />
      </div>
    </div>
  )
}

/** A cloud with its flat underside pre-faded into the image alpha, so it dissolves without runtime masks. */
function Cloud({ className }: { className: string }) {
  return (
    <img
      src={CLOUD}
      alt=""
      width={1120}
      height={403}
      decoding="async"
      className={cn('absolute max-w-none', className)}
    />
  )
}
