# NuvioTV Custom Fork Features

This public repository is based on [ysosrs123/NuvioTV-Fork](https://github.com/ysosrs123/NuvioTV-Fork).
Upstream code, license notices, and attribution remain in place. Support for this custom fork is
provided through its issue tracker and release discussions. The following describes the
additional work maintained in this fork.

## Post-play recommendations

The post-play screen keeps Nuvio's original full-screen hero presentation and adds a source picker:

- **Auto** prefers Kurato AI, then BingeCat AI, then the existing fallback providers.
- **Trakt** and **TMDB** remain directly selectable.
- **Kurato AI** and **BingeCat AI** are discovered from installed Stremio addons rather than from a
  hardcoded private manifest URL.
- Catalog pagination continues until the provider is exhausted, reports no more results, or stops
  making progress. Recommendations are not artificially capped at four items.
- Details are resolved lazily for the selected recommendation and a small prefetch window, keeping
  the hero responsive while preserving rich artwork and ratings.

## Trailer resolution

Trailer resolution follows the same metadata path used by Nuvio's detail/player screens:

1. Use trailer IDs returned by the selected recommendation catalog.
2. Use trailer IDs from the metadata addon selected by Nuvio's all-addon metadata routing.
3. Fall back to Nuvio's TMDB/IMDb trailer resolver.

This keeps the Trailer action available for Kurato/BingeCat recommendations even when their Meta
endpoint is incomplete or provider-specific IDs cannot be mapped to TMDB.

## Progressive AIOStreams scraping

The matching AIOStreams fork exposes cumulative stream snapshots through its Nuvio progressive
endpoint. Nuvio consumes those snapshots without discarding earlier results, updates the source
list as new snapshots arrive, and keeps the normal manifest path available as a fallback.

The progressive path is an integration contract between these two repositories; it is not expected
to work against an unrelated AIOStreams build that does not implement the endpoint.
For a visual example of the intended progressive source-list behavior, see the
[Nuvio progressive scraping video](https://www.reddit.com/r/Nuvio/s/DSBDbVt2MY).

## Scrape timeout controls

The user can choose instant, bounded, or unlimited source-selection waiting. Unlimited means that
Nuvio continues considering addon responses while they are active; it does not remove transport,
coroutine, or UI safety guards.

## Build variants and APK naming

The public release channel uses explicit ABI-specific APK names:

- `NuvioTV-Custom-<version>-armeabi-v7a.apk`
- `NuvioTV-Custom-<version>-arm64-v8a.apk`

Do not commit signed APKs, keystores, local properties, API keys, or private manifest URLs. Public
APKs are uploaded as GitHub Release assets by the release workflow.

## Compatibility and attribution

This remains a GPL-3.0 project derived from NuvioTV and ysosrs123/NuvioTV-Fork. The custom features
are maintained as a personal integration layer and are not presented as official NuvioTV features.
