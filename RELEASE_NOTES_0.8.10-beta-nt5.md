# NuvioTV Custom 0.8.10-beta-nt5

This release packages the verified Seekr frame-calibration work on top of the existing custom fork.
It is built from the `custom-dimmer` branch and preserves the prior top navigation, calendar,
dimmer, skip-metadata, playback, HDR, audio, and recommendation features.

## Seekr improvements over a normal integration

- Adds automatic per-release Preview Sync using frames rendered by the active ExoPlayer surface.
- Samples three timeline anchors and searches the documented Seekr keyframe drift window (`-3s`,
  `0`, `+3s`) instead of trusting a single cue blindly.
- Compares low-resolution luminance fingerprints, rejects weak/ambiguous matches, and combines
  anchors with a median/MAD outlier filter before applying the offset.
- Keeps the existing manual Preview Sync control for fine 250 ms adjustments, held-key coarse
  adjustments, reset, and duration-gap suggestions.
- Resets calibration for every new release and safely falls back to zero offset when evidence is
  insufficient.
- Captures only the existing local player surface at 320×180. No stream proxy, frame upload,
  second decoder, or server-side video processing is used.
- Reduces startup work to three candidates per anchor while retaining the ±3-second correction
  window.

## Playback and reliability

- Preserves the previously shipped custom D-pad, HDR/Dolby Vision, subtitle extractor, skip
  metadata, calendar, top-navigation, app-dimmer, and post-play recommendation work.
- Verified unit coverage for median/outlier rejection, ambiguous-match fallback, and source-scale
  conversion.
- Verified on the connected Android TV test device: Seekr API/signed sprite resources returned
  successfully, the calibration gate completed, playback resumed, and no fatal, PixelCopy, or
  process-crash errors were observed.

## Important limitation

Seekr’s own thumbnails are discrete and may be keyframe approximations or come from a different
release. No client can guarantee pixel-perfect identity in that case. This release applies an
offset only when local evidence is strong and otherwise leaves the original timing unchanged.
