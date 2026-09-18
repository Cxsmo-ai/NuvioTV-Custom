# NuvioTV Custom 0.8.10-beta-nt7

This release addresses profile avatar resilience, tracker credentials input on Android TV remotes, per-episode ratings and TMDB enrichment fallback, and fully updates the in-app About experience.

## Tracker App Credentials Input Fix

- Fixed D-pad focus conflict on the Tracking Credentials dialog so remote clicks properly activate the software keyboard.
- Integrated explicit keyboard controller actions with Next/Done navigation across Trakt ID, Trakt Secret, and Simkl ID.
- Added a Clear button to reset saved credentials and updated the tracking settings overview to display which providers are configured.
- Hardened credential storage encryption with safe fallback to prevent crashes on non-standard KeyStore implementations.

## Per-Episode Ratings & TMDB Enrichment

- Implemented automatic TMDB episode ratings fallback so TV shows display rating badges even when external IMDb servers are unconfigured.
- Parsed episode vote averages and vote counts directly from TMDB TV season payloads into `Video` models and `EpisodeRatingsSection`.
- Upgraded TMDB metadata enrichment with intelligent display label resolution to avoid unwanted CJK/Hangul fallbacks for non-CJK locales.
- Restored movie collection metadata enrichment with release-ordered parts and localized collection names.

## Profile Avatar Resilience

- Prioritized stable catalog avatar IDs over stale or expired avatar URLs to eliminate broken profile pictures.
- Added non-destructive avatar catalog retry handling to retain existing avatars across transient network failures.
- Provided graceful initial-letter fallbacks whenever an avatar image cannot be fetched.

## Updated About Experience

- Rebranded in-app About screen to NuvioTV Custom by Cxsmo-ai, with full attribution to ysosrs123/NuvioTV-Fork and Tapframe.
- Added direct navigation links to both the custom GitHub repository and the upstream fork.
- Highlighted custom features: Top Navigation Bar, Full App Dimmer, TV Calendar, Seekr Preview Sync, Multi-Provider Skip Intro, and Progressive Scraping.

## Upstream Fixes

- Scoped watched-episodes cache validation to the active profile ID to prevent cross-profile history pruning.
