# Subtitle rendering compatibility

This change keeps libass opt-in and preserves the existing `useLibass = false`
default. It improves the Media3 fallback path and makes the optional libass path
safe with the custom Dolby Vision extractor.

## What changed

- The HDR color-signaling extractor can now be inspected and recursively mapped.
- ASS-aware Matroska replacement descends through that HDR wrapper, preserving the
  Dolby Vision sample transformer and the vendored DTS detection.
- When libass is disabled and an ASS/SSA track is selected, Media3 keeps the cue's
  embedded sizes and positioning instead of applying SRT-style overrides.
- Delayed subtitle padding updates are generation-checked so an older track cannot
  overwrite a newly selected track.

## Compatibility

The setting remains user-controlled. Users who have enabled libass keep the
libass media-source path; users who have not enabled it continue using Media3's
fallback renderer. The fallback supports embedded ASS styling but does not provide
all libass features such as complex animation, attached-font fidelity, or advanced
typesetting.

## Verification

Focused tests cover extractor-wrapper preservation and resetting Media3 subtitle
overrides. Playback verification should cover ordinary ASS, SSA, SRT, and WebVTT;
ASS → SRT → ASS switching; embedded fonts; non-16:9 video; and Dolby Vision/HDR10
Matroska files on compatible Android TV hardware.
