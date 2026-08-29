
# NuvioTV Custom

An independently maintained Android TV fork of [ysosrs123/NuvioTV-Fork](https://github.com/ysosrs123/NuvioTV-Fork), with the upstream project kept as the compatibility baseline and additional features maintained for the Cxsmo AIOStreams ecosystem.

[![Android CI](https://github.com/Cxsmo-ai/NuvioTV-Custom/actions/workflows/android-ci.yml/badge.svg)](https://github.com/Cxsmo-ai/NuvioTV-Custom/actions/workflows/android-ci.yml)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)

## About

This is a **publicly available fork** of [ysosrs123/NuvioTV-Fork](https://github.com/ysosrs123/NuvioTV-Fork),
itself derived from [NuvioTV](https://github.com/NuvioMedia/NuvioTV), with fixes, modifications,
and optimisation maintained for the Cxsmo AIOStreams ecosystem. Support is provided for this
custom fork through its repository issue tracker and release discussions.

The focus is **speed, efficiency, and optimised playback of high-bitrate 4K remux video and
lossless bitstream audio** -- the kind of content (100 GB+ remuxes, TrueHD/DTS-HD MA/Atmos/DTS:X
passthrough, Dolby Vision) that stresses the parts of a player most builds don't push hard.

**Will likely not work well for Anime, or on low-end devices. Features that write to cache will add wear to internal storage**

NuvioTV itself is a modern, TV-first media player for Android TV, written in Kotlin. It acts as a
client-side playback interface that integrates with the Stremio addon ecosystem for content
discovery and source resolution through user-installed extensions.

**Please support the official project -- [NuvioMedia on GitHub](https://github.com/NuvioMedia).**
This fork is a set of targeted optimisations layered on top of their work, not a replacement for it.

---

## What this fork adds

The fork-specific features are documented in [docs/custom-fork-features.md](docs/custom-fork-features.md).
In addition to the playback and library work below, this fork includes:

- **Post-play recommendation source selection** -- choose Auto, Trakt, TMDB, Kurato AI, or BingeCat AI.
- **Kurato and BingeCat discovery** -- the app detects their installed Stremio catalogs, including when they are exposed through AIOStreams, and uses their full paginated result set rather than a four-item rail.
- **Metadata-aware trailers** -- post-play trailers use the same all-addon metadata routing as Nuvio's detail/player screens, then fall back to addon-supplied, TMDB, or IMDb trailer sources.
- **Progressive AIOStreams scraping** -- the stream screen can consume cumulative NDJSON snapshots from the forked AIOStreams progressive endpoint, so usable results appear while slower addons continue.
- **Unlocked user scrape timeout** -- the user-facing timeout choices include instant, bounded, and unlimited modes; internal safety bounds remain in place to prevent a dead UI.

Progressive scraping requires the matching [Cxsmo-ai/AIOStreams](https://github.com/Cxsmo-ai/AIOStreams)
fork and its `client=nuvio-progressive` endpoint. A normal AIOStreams manifest remains supported.

Optimisation and playback-quality work, most of it aimed at high-bitrate remux and lossless audio.
The short version: streams start faster, buffer deeper, stall less, and you can *see* that your
device is genuinely delivering lossless audio and the right HDR -- without needing to be the kind
of person who tunes buffer settings for fun.

- **Lighter, faster build** -- unused engine components stripped out, so the app itself is smaller
  and snappier (see *What's been removed*).
- **Device settings assessment -- your settings, tuned for *your* hardware in one press.** Stop
  guessing what connection count, buffer size or Dolby Vision mode your box wants: the assessment
  measures your link against the last thing you actually played, reads what your device and
  display genuinely support, and lays out recommended settings with the reasoning next to every
  one -- what was measured, what was calculated from your hardware, what's a personal trade-off
  (pick a buffer profile; it'll suggest one based on how steady your connection looked), and what
  it can't honestly know so won't touch. Like the answers? Apply them all in one press. Change
  your mind? Revert restores every previous value, even after a restart. You stay in charge; it
  just does the homework.
- **MDBList watch tracking, alongside Trakt and Simkl** -- scrobbling, Continue Watching, resume,
  watched ticks and Up Next, on nothing more than a free MDBList API key. Ported onto upstream's
  native pluggable tracking architecture: every connected tracker gets every write, and switching
  your Watch Progress source to MDBList only ever adds watched state, never wipes it.
- **Audio that fits your gear** -- per-format passthrough switches (Dolby Digital, DD+, TrueHD,
  DTS, DTS-HD) phrased as questions about your receiver, following Kodi's audio settings. Android
  treats passthrough as all-or-nothing, which is no help when your receiver handles every Dolby
  format but no DTS, or takes DTS core but not DTS-HD. Switch off what your gear can't decode and
  Nuvio decodes just those in the app -- with its own decoder, because some boxes ship a vendor
  DTS decoder that quietly folds 5.1 to stereo. A diagnostics row shows what your chain actually
  claims it can take, so you can tell a lying EDID from a real limitation.
- **Fewer stalls, less wasted bandwidth** -- upstream's parallel downloading kept too little data
  queued ahead of playback and threw away chunks it had already paid to download (measured ~47% of
  transfer wasted on a 4K remux). This fork keeps the pipeline properly fed and stops the
  re-downloading, so the buffer builds close to the speed your source can deliver, holds through
  bitrate peaks, and roughly halves the debrid data burned per title. If a debrid CDN rate-limits
  you mid-film, speeds now recover on their own instead of staying throttled to the credits. Plus
  off-heap buffers and a seek fix that makes non-faststart MP4s actually watchable.
- **A speed test that tells you what to do about it** -- not a generic number from a test server:
  an adaptive sweep of connection and chunk-size combinations against the *actual* stream you last
  played, over the same path playback uses. It finds the cheapest configuration that comfortably
  sustains the title and says so in plain terms -- and on memory-tight boxes it's smart enough to
  prefer a config that leaves room for a deeper playback buffer, telling you the trade in MB and
  seconds. It also watches how steady your connection held during the test, which feeds the
  assessment's buffer suggestion. Slow source or buffering bug? Now you can tell them apart.
- **Better streams picked for you, automatically** -- source filtering and sorting re-baselined
  around TRaSH-guides-aligned release-group quality tiers, so the best-quality trustworthy release
  floats to the top and auto-play grabs the right one without you reading twenty filenames. Safe
  to experiment: one tap resets to the recommended baseline, another shows everything unfiltered
  while keeping best-first ordering, and the auto-play settings link straight to the quality rules
  that drive them.
- **Dolby Vision that just works** -- app-side Profile 7 and Profile 5 -> 8.1 conversion via
  libdovi with no per-frame stutter, correct enhancement-layer handling for single-track remuxes,
  and fixes for cases that silently produced wrong or static output before. DV titles look the way
  they're meant to on hardware that was never sold as supporting them.
- **Lossless audio you can trust** -- a hardened bitstream passthrough stack (TrueHD, DTS-HD MA,
  Atmos, DTS:X) that resists mid-playback renegotiation dropping you to lossy without telling
  you, format detection that works on native-DV boxes, a hi-res AC-3 transcode fix, and automatic
  selection of the lossless track so you don't start every film in the wrong audio.
- **Kodi-style MAT audio** -- an app-side MAT / IEC61937 packing path (ported from Kodi's
  CPackerMAT) that takes TrueHD delivery out of the hands of flaky vendor audio drivers. Behind an
  Advanced toggle, off by default, experimental -- for when your box's HAL is the problem.
- **Judder-free frame rates** -- precise 23.976-vs-24 matching so films play at their native
  cadence, seamless-switch detection, and a settle-then-resume that stops eARC/soundbar chains
  dropping audio while HDMI renegotiates.
- **MP4s that seek properly** -- non-faststart / poorly-interleaved MP4s no longer thrash and
  stall every time you skip around.
- **Faster stream start** -- the app searches, ranks and pre-resolves your stream while you
  browse, opens the network connection at the press, and no longer over-fetches the file's tail
  index -- so pressing play reaches the player in a few hundred milliseconds instead of a couple
  of seconds.
- **Pressing play reaches the player faster again** (~6.4 s -> ~2 s on a prepared press) --
  streams are found, ranked and resolved while you're still browsing, and the content-server
  connection is warmed to the right node ahead of time.
- **Smoother browsing** -- home-grid scroll-jank reduction and poster prefetch/pre-decode, so the
  UI keeps up with your remote.
- **Stats-for-nerds overlay -- proof, not vibes.** A live diagnostics HUD built for
  high-bitrate/lossless content: measured video bitrate, the audio codec by name with its measured
  passthrough bitrate, whether bitstream is *genuinely* reaching your AVR, HDR/Dolby Vision
  detection, live network throughput and buffer health, the negotiated audio path, SoC stats to know when your SoC is being throtled, audio-clock jitter and underrun cross-checks. When it says
  TrueHD Atmos is hitting the sink, it measured it. The assessment configures; this overlay
  verifies.

## Stats for Nerds Overlay -

<img width="1920" height="1080" alt="IMG_3835" src="https://github.com/user-attachments/assets/623b334f-01fd-464f-a49a-c58eb5548e59" />

## MDBList Tracking Integration -

<img width="1920" height="1080" alt="screenshot2" src="https://github.com/user-attachments/assets/808ff170-cac4-4d0e-9f68-412e761748bc" />

## Revised Last Played Stream Speed Test:

<img width="1920" height="1080" alt="screenshot1" src="https://github.com/user-attachments/assets/1ccb4258-7487-4424-82cb-496a776e3c4f" />

## Device Assessment:

<img width="1920" height="1080" alt="screenshot4" src="https://github.com/user-attachments/assets/eb274129-553c-4b5c-b1ac-665aca9101fa" />

## Per-Format Audio Passthrough Switches:

<img width="1920" height="1080" alt="Screenshot_20260826_084841" src="https://github.com/user-attachments/assets/85f3a839-11bb-45f6-9154-3d471efe2319" />

---

## What's been removed

- **Home layout picker** -- the app is now **Modern-layout only**. The picker was removed from both
  first-run setup and Settings. The Modern-specific options (landscape posters, full-screen hero
  backdrop, poster/card styling) remain.
- **IAMF** and **MPEG-H** audio decoder components, and an unused UI component -- dropped to slim
  the build. No user-facing loss.
- **Android TV channel / preview-program sync** -- not used on this fork's target setup.
- **In-app update check** -- disabled, because this is a personal fork; it does not phone the
  upstream update feed.

**Not removed** (sometimes assumed otherwise): all **34 language translations**, the **add-on
manager**, and **subtitle add-on support** are intact. Only the layout picker was removed.

---

## Target hardware

Primary/validated target is the **Amlogic S905X4 / 4GB RAM, Amlogic S905X5M / 2GB RAM class (armeabi-v7a) and AmLogic S905X5-J / 4GB RAM (armeabi-v8a)**, tested on a Homatics Box R
4K Plus, Xiaomi Box S 3rd Gen, and Ugoos AM9 Pro in a Samsung HW-Q800F -> LG C9 eARC chain. Other devices (Nvidia Shield, Prism+, Xiaomi) have been
community-tested.

---

## Licence

This fork is derived from [NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV) and is licensed
under the **GNU General Public License v3.0**. All upstream copyright notices, licence headers, and
author attributions are preserved. Full credit to the upstream maintainers and contributors.
