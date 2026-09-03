# Version 7 — root cause found and fixed

The playback failure is finally identified from the live service, not inferred. Development network
access to 25-hd.com and zmdb.net worked in this session, so the whole chain was replayed for the
movie the user reported (`god-skin-2026`, ZMDB `id=1278971&type=movie`):

| Request | `g.zmdb.net` (gateway in `data.hlsUrl`) | `lb.cdn-osxpsmd000{1,2}.space` (steered hosts) |
| --- | --- | --- |
| `_master` | 200 | 403 |
| `_index` (variant playlist) | 200 | 200 |
| `hdr.bin`, `seg_*.bin` (media) | **403** | 200 |

The master declares `#EXT-X-CONTENT-STEERING:SERVER-URI="/hls/playback-routing.json?gw_enc=o1"`, and
that document's `PATHWAY-CLONES` replace the host for everything below the master. The gateway serves
playlists only and answers 403 for every media byte, so a player that does not apply content steering
requests segments from the gateway and fails — which is exactly the reported
`ERROR_CODE_IO_BAD_HTTP_STATUS (2004)`. No header, referer, `Range` or query variation changes the
gateway's 403; the split is by role, not by authorisation.

Fix: `HlsGateway` (the interceptor returned by `getVideoInterceptor`) reads the steering document the
master itself declares, sends a refused media request again against each declared host in priority
order, and keeps using the host that answers. The master and the steering document are never moved,
because the replacement hosts answer 403 for the master. `HlsSteering` parses both documents. The v5
master preflight (`HlsCheck`) is removed: it could not have detected this, since the master is the one
resource the gateway does serve.

Also observed and accounted for: `_index` carries `sig`/`exp` and stops working about five minutes
after the master is read, while media segments need no signature at all. Emitting per-quality variant
playlists as links was therefore rejected — the master stays valid and the player re-reads `_index`
itself.

Validation: 15 Kotlin cases for the new code (5 steering-parsing, 4 gateway-routing, 6 existing
diagnostic cases retargeted at the new interceptor) plus 5 Python release cases pass locally. The full
chain was then replayed against the live service through the real interceptor: master 200, variant
200, init segment 200, first media segment 200 — 6 requests, 5 successful, one recorded 403 followed
by the reroute to `lb.cdn-osxpsmd0002.space`. Before this change the same two media requests were 403.

Limits: Cloudstream's downloader does not use `getVideoInterceptor`, so downloads are expected to keep
failing; only playback is fixed. Android playback itself is still untested on a device, and the
diagnostic report (search `25hd-debug`) remains the way to check it. Casting may not use this hook.

---

# Version 6 diagnostic build

User confirmed v5 still returns Media3 IO_BAD_HTTP_STATUS (2004). Actual HTTP response and failed resource remain unknown.

Changes: replace the extra master preflight with an in-memory playback interceptor report. Search `25hd-debug` in 25-HD after failure to view it. The local report page performs no network request and each search creates a fresh report URL to avoid cached detail pages.

Initial run 33774565832 compiled but three MockWebServer cases failed at construction with NoClassDefFoundError. The first replacement used jdk.httpserver, which the Android compile SDK does not expose (run 33774909326). Final tests use a loopback GET server based on java.net.ServerSocket; production code is unchanged. Validation passed on final source commit `2fb466b16a5cf7e285b7902acaecb9ba78eb3d5f`: 43 Kotlin tests and 5 Python release tests; CS3/JAR generation and ensureJarCompatibility passed. Six local HTTP server/unit tests cover child HTTP failure, unchanged body/headers, no extra requests, redirects, key/master classification, partial success, exception-message redaction, bounded sessions and an empty report. Five Python release tests passed locally. Existing parser/client checks are retained.

The upstream interceptor hook selects OkHttp instead of Cronet. No server policy or authentication changes are made. Android playback and report visibility still require user testing; casting/downloads may not use this hook. Prior v5 preflight helper tests are retained but that helper is no longer called by the provider.

Successful build and publication: https://github.com/Banchon999/Thai-Movie-Extension/actions/runs/33775153471

Published metadata verified version=6, status=3. CS3: 61,462 bytes, SHA-256 `b0590cfc936205a9e19bb3d5deee08d77a149034f06a4d95202b24834db9d81b`. JAR: 240,609 bytes, SHA-256 `7204475379324eef3b9917dc9bf11a983b36381767a3a9329215da24be67e603`.

---

# Version 5 diagnostics — 2026-09-03

User screenshot: Cloudstream reports `ERROR_CODE_IO_BAD_HTTP_STATUS (2004)`.
This is a player error code, not the origin server's HTTP response status. The actual HTTP status,
failed host and failed resource (master/variant/key/segment) cannot be identified from that screenshot.

Changes: preflight explicit ZMDB HLS masters with the existing playback headers; reject non-2xx
responses and HTTP-200 non-HLS bodies before emitting a link. Report actual status and host while
omitting signed paths, queries and response bodies. A failed master does not skip another declared server.

Limits: this adds one GET before playback; single-use URLs may need different handling. It does not
inspect variants, keys or segments and is not a confirmed playback fix. No live blocked-site requests
were made in development, and no header/domain changes were guessed.

Validation: PASS — 37 Kotlin cases (including 4 new HLS diagnostic cases), 5 Python release cases,
CS3/JAR compilation and compatibility validation. Build and publish jobs succeeded.
Public plugins.json verified version 5, status 3.

Source commit: `f86b03ac03e97995b241ae34b870eabc0e376adc`.
Workflow: https://github.com/Banchon999/Thai-Movie-Extension/actions/runs/33772628751
CS3: 58,111 bytes; SHA-256 `18985241257da2af95096f7d73b8a69fbea11c1daae6d286ef5bdbdd523fbad5`.
Android retest and the actual failing HTTP response remain pending.

# Version 4 validation — 2026-09-03

ZMDB bootstrap-to-HLS integration using user-supplied response contracts. Tokens are fetched anew
per play and are not persisted in source, fixtures, or serialized episodes.

- CI: PASS — 33 Kotlin cases, 5 Python cases, CS3/JAR compilation and compatibility checks.
- Publication: PASS; public plugins.json verified version=4, status=3.
- Release source: `1f60cb2a08b92ba894138f69f3314a2620f0c4b4`.
- Workflow: https://github.com/Banchon999/Thai-Movie-Extension/actions/runs/33769746495
- CS3: 57,417 bytes; SHA-256 `e1608e9b07f79a1833da9fe078cd03719c9d552d189842dc8eb71baed5c55e9d`.
- JAR: 220,679 bytes; SHA-256 `5ed330fce236211939b5047ae44c4190b3e0181b7c28169c72723610f4ace6d6`.
- Added 10 bootstrap/client tests to the previous 23 Kotlin cases (33 total).
- Full request sequence is exercised using an injected fake transport, not the live blocked site.
- Movie bootstrap behavior is synthetic; TV bootstrap fields come from user-supplied HTML.
- Android playback, playlist gateway, audio/subtitle selection and CDN headers remain unverified.

# Version 3 validation — 2026-09-03

Changes: prefer visible detail posters over inconsistent OpenGraph images; use responsive
WordPress image variants and image request headers; keep series details accessible with an
explicit unsupported-episode notice; retain HTML fallbacks when an extractor throws;
recognize the observed `data-embed-url` player attribute.

User feedback: search and descriptions work; some covers are missing; movie playback fails;
series episodes are selected through buttons inside the external player.

- Python release tests: PASS locally (5 cases).
- Kotlin tests: PASS (`testDebugUnitTest`, 16 parser cases).
- CS3/JAR compilation, compatibility validation and publication: PASS.
- Source commit: `e83bf8535ad76aefc15ed7cd55308ef243a61890`.
- Workflow: https://github.com/Banchon999/Thai-Movie-Extension/actions/runs/33753219089
- Published `plugins.json` verified: version 3, status 3.
- CS3: 39,264 bytes; SHA-256 `2b647796d8445ec7dae9603ace8a20fbfd235e876674a34b50538c7133db55e1`.
- JAR: 141,488 bytes; SHA-256 `5e0ec6cbab4af0236098ddaa859e1c943da8afcef8e767b7f3f63b2b9ea9f1d1`.
- Added 2 real-poster regression tests and 2 focused parser edge cases (16 Kotlin cases total).
- The series metadata change and extractor failure fallback are compiled in CI; Android runtime behavior is not yet tested.
- Playback and dynamic episode selection remain unresolved. ZMDB blocked the development browser.
- No claim that poster selection fixes every missing cover; device retest is still needed.

# Version 2 validation — 2026-09-03

Version 2 fixes `.movie_box` cards, full search titles, movie-heading priority, metadata and deferred iframe discovery.
Browser inspection succeeded for the homepage (54 cards), Thai search (3 results), empty search, movie and series pages.
Five captured-markup regression tests were added; all passed together with the seven existing Kotlin cases.

Source commit: `532936d6560efd1a701bf8e3da82f7ddb947f005`

Successful workflow: https://github.com/Banchon999/Thai-Movie-Extension/actions/runs/33751524164

| Check | Version 2 result |
| --- | --- |
| Kotlin parser tests | PASS — 5 captured-markup cases plus 7 existing cases |
| Python release tests | PASS — 5 cases |
| Kotlin / CS3 / cross-platform JAR build | PASS |
| Publication | PASS — build and publish jobs successful |
| Public plugins.json | Verified version=2, status=3 |
| CS3 | 36,900 bytes |
| JAR | 133,453 bytes |
| Android catalogue display | Awaiting user retest; captured-markup parsing passed |
| Video playback / ZMDB TV episodes | Unverified; ZMDB returned a Cloudflare security block in the test browser |

CS3 SHA-256: `39d1d4b6e4db6b6530e53f238e4d45620c9309b1c61a64bbb182a00525baacb0`

The captured fixtures are rendered DOM excerpts, not complete HTTP responses. The tests establish that
these actual 25-HD card/title/player structures are parsed correctly; they do not establish video playback.

## Previous version 1 build record

Source commit: `fb29131785f2317f826286cfadb327df1f11ca9b`

Successful workflow: https://github.com/Banchon999/Thai-Movie-Extension/actions/runs/33750191854

| Check | Result |
| --- | --- |
| Source publication | PASS — Banchon999/Thai-Movie-Extension, main branch |
| Python release tests | PASS — 5 tests on GitHub Actions |
| Kotlin parser tests | PASS — testDebugUnitTest; 7 synthetic-fixture cases |
| Kotlin compilation | PASS — JDK 17, Gradle 8.12, Android SDK 35 |
| CS3 generation | PASS — TwentyFiveHD.cs3, 36,414 bytes |
| JAR generation / compatibility | PASS — TwentyFiveHD.jar, 131,728 bytes; ensureJarCompatibility passed |
| Release validation | PASS — DEX header, plugin manifest, file sizes and SHA-256 hashes |
| Publish workflow | PASS — build and publish jobs completed successfully |
| Public repository manifests | PASS — repo.json and plugins.json fetched from builds branch |
| First publish / repeat behavior | PASS — locally exercised the workflow's publishing script with a temporary bare Git repository |
| Live 25-hd.com inspection | HTTP 403 in the development environment; no site HTML obtained |
| Android playback | NOT TESTED |

Published CS3 SHA-256:
`0b472a903383627f47f0d11eff055b8b7d0372cece1871745260766e9d9dbb41`

The Python tests verify release staging, not provider functionality. The Kotlin tests verify
parser behavior against synthetic HTML, not the current 25-hd.com website.

The initial build caught an incorrect trailing lambda in newEpisode(). The successful build
uses an explicitly named initializer argument and preserves fix=false for serialized playback data.

The source now compiles and produces installable artifacts. This does not establish successful
playback: live HTML, actual selectors, episode behavior and player extraction still require testing.
Provider metadata remains status=3 (beta). Do not mark stable until live playback succeeds.
