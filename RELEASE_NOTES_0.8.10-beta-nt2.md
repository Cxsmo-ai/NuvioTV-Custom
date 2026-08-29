# NuvioTV Custom 0.8.10-beta-nt2

This is the first repository release for the NuvioTV Custom fork. It is based on
[ysosrs123/NuvioTV-Fork](https://github.com/ysosrs123/NuvioTV-Fork) and includes the
custom post-play recommendation and AIOStreams integration documented in
[docs/custom-fork-features.md](docs/custom-fork-features.md).

## Highlights

- Select Auto, Trakt, TMDB, Kurato AI, or BingeCat AI for post-play recommendations.
- Use full paginated Kurato/BingeCat recommendation results without a four-item cap.
- Resolve trailers from the selected catalog, Nuvio's configured metadata addons, then TMDB/IMDb.
- Show AIOStreams progressive stream results while the remaining providers continue scraping.
- Support instant, bounded, and unlimited user-facing scrape timeout modes with safety guards intact.
- Keep the original Nuvio full-screen post-play hero layout.

## APK assets

The release workflow publishes ABI-specific assets with these names:

- `NuvioTV-Custom-0.8.10-beta-nt2-armeabi-v7a.apk`
- `NuvioTV-Custom-0.8.10-beta-nt2-arm64-v8a.apk`

The `armeabi-v7a` build is intended for the current Android TV test device. The arm64 build is
provided for compatible Android TV hardware. Progressive scraping requires the matching
[Cxsmo-ai/AIOStreams](https://github.com/Cxsmo-ai/AIOStreams) fork.

## Upgrade notes

This fork uses the existing `com.nuvio.tv.test.debug` application identity for the current test
channel. Install the ABI matching the device. Do not install both APKs on one device.
