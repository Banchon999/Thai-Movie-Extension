# ZMDB response support — development work

Evidence supplied by the user on 2026-09-03:

- A successful `/api/embed/links?id=108978&type=tv&season=1&episode=7` response
  contains `playerEmbedLinks`, `seasonNumber`, `episodeNumber`, and a temporary `linkToken`.
- The two public player links use the same stream037 video ID but distinct audio/subtitle
  query parameters. They must not be deduplicated by video ID alone.
- A screenshot of a successful `/api/video/<id>` response shows `data.hlsUrl`,
  `availableQualities`, `ladderComplete`, `name`, and `seekPreview`.
- The `hlsUrl` is an extensionless master URL with a query. The preview playlist and VTT
  belong to seek thumbnails. They are not playback or dialogue-subtitle fallbacks.

Implemented in this development branch:

- Parse the observed video and episode-link response shapes.
- When provider traversal reaches a known ZMDB `/api/video/<id>` URL, request and parse
  that response and emit its `data.hlsUrl` with `ExtractorLinkType.M3U8` explicitly.
- Preserve the exact master URL/query; do not construct playlist filenames from `.bin` segments.
- Retain each audio/subtitle player URL intact. Do not return links marked VIP-only.
- Never persist the user's bearer/link tokens. Fixtures replace tokens, IDs and stream URLs
  with inert examples while preserving the supplied response structure.

Not yet implemented or verified:

- Initial embed metadata request and fresh token acquisition/refresh.
- Enumerating all seasons and episodes. The links response describes a single selected episode.
- Connecting the episode-link response and stream037 page to the video API request automatically.
  The video API handler is available when that API URL reaches traversal; normal 25-HD catalogue
  entries do not yet supply it. The episode-links parser is prepared for the missing integration.
- Actual playlist response, any gateway-specific processing, audio/subtitle selection and playback
  headers. The `gw_enc` parameter alone does not establish what processing is required.
- Android playback. The developer browser remains blocked at ZMDB, and direct stream037 navigation
  returned an unauthorized-source denial. No access-control bypass was attempted.

Validation: GitHub Actions run 33765073964 passed 23 Kotlin parser cases (7 new, 16 existing),
5 Python release tests, CS3/JAR compilation and compatibility validation. Publication was skipped.
https://github.com/Banchon999/Thai-Movie-Extension/actions/runs/33765073964
This is a draft implementation, not a new published plugin release. The published installer remains version 3.
