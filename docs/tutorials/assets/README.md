# Vendored assets

Third-party, committed so the docs site plays casts offline (no CDN).

- **asciinema-player.min.js**, **asciinema-player.css** — [asciinema-player](https://github.com/asciinema/asciinema-player),
  version pinned in `.version` (currently 3.15.1). Licensed under the
  [Apache License 2.0](https://github.com/asciinema/asciinema-player/blob/master/LICENSE).
  Fetched from `https://cdn.jsdelivr.net/npm/asciinema-player@<version>/dist/bundle/`.

To upgrade: bump `.version`, re-fetch both files from the jsdelivr URL above,
then `bb tutorial:embed`.
