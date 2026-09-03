# ZMDB integration — version 4 beta

The user supplied the complete TV bootstrap HTML, episode-links JSON, a video API request,
and a screenshot of its response. Fixtures preserve the observed fields but replace credentials,
internal IDs, and signed URLs with inert examples. The token pasted in chat is not stored.

Implemented flow:

1. Load the declared ZMDB iframe and parse `script#bootstrap` as JSON.
2. Enumerate `content.seasons[].episodes[]` with `hasLinks=true`, preserving season/episode numbers.
3. Serialize only the embed URL, referring page and selected season/episode into Cloudstream episodes.
4. On play, load a fresh bootstrap, use `query.id` and `query.type`, and send the temporary `linkToken`
   as Authorization only to the episode-links API. Refresh once on 401, never on 403.
5. Validate the response identifies the requested episode. Read the video ID from returned
   `stream037.com/play/<id>` links and call the observed ZMDB `/api/video/<id>` endpoint.
6. Emit `data.hlsUrl` with an explicit M3U8 type and preserve its complete URL/query.
   Do not use seekPreview as video/subtitles, and do not construct filenames from `.bin` segments.

Audio/subtitle variants sharing a video ID yield one master. Automatic selection matching the
stream037 audio/subtitle query is not implemented; available tracks depend on the actual playlist.
Other player hosts are not guessed. Failed video servers do not prevent trying another declared server.
The client never persists tokens, forwards Authorization to the CDN, or prints response bodies in errors.

Validation: pending version 4 CI. Added transport-injected tests for the full episode 7 request sequence,
one-time token refresh, 403 handling, wrong/missing episodes, title mismatch, unavailable episodes,
numeric sorting across seasons, and cancellation. Movie request handling is covered by a synthetic
fixture because a complete movie bootstrap has not been captured.

Remaining runtime limits: development-browser access is blocked by ZMDB and direct stream037
navigation is denied. No live calls to these blocked sites were made during this change. Real
playlist contents/gateway processing (`gw_enc`), CDN headers, Android playback, audio and subtitles
are unverified. Version 4 is beta and requires a device test; successful CI is not proof of playback.
