# NuvioTV Custom 0.8.10-beta-nt6

This is the production-signed follow-up to the `v0.8.10-beta-nt5` TV benchmark. It includes the
same verified Seekr frame-calibration release and is built by the protected GitHub Actions release
workflow using the fork's encrypted signing and backend configuration secrets.

## Seekr frame calibration

- Automatically aligns Seekr preview timing to the actual rendered ExoPlayer release when local
  evidence is strong enough.
- Uses three safe timeline anchors and searches `-3s`, `0`, and `+3s` around each cue to cover
  documented keyframe drift.
- Uses low-resolution luminance comparison plus robust median/outlier filtering.
- Rejects weak or ambiguous matches and falls back to the original timing without affecting
  playback.
- Keeps manual Preview Sync controls for fine adjustment and resets calibration for each release.
- Uses the existing player surface only: no stream proxy, second decoder, frame upload, or server
  processing.

See the [custom feature guide](docs/custom-fork-features.md) for the comparison with normal Seekr
integration and its limitations.
