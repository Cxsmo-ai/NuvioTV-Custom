# NuvioTV Custom 0.8.10-beta-nt8

This release introduces a dedicated Random Episode feature for series, including season-scoped selection, unwatched filtering, and a hidden "Mystery" mode.

## Random Episode Features

- **Hero Action Button:** Added a shuffle icon button in the series hero banner to instantly open the Random Episode dialog.
- **Season Tab Button:** Added a matching Random button on the season tabs row for quick season-targeted random selection.
- **Scope Options:** Select between picking from all seasons (specials excluded if regular seasons exist) or only from the currently focused season.
- **Unwatched Filter:** Toggle to only pick from episodes not yet marked as watched (falls back automatically to all episodes if everything has been watched).
- **Hidden Random (Mystery Mode):**
  - Directly launches into automated stream selection and playback without revealing the chosen episode's number, title, overview, or thumbnail.
  - Replaces episode titles in the player, pause overlay, and loading screens with "Mystery Episode" to prevent spoilers.
  - Persisted per-profile and configurable directly in the dialog as well as under **Settings → Layout → Detail Page**.
