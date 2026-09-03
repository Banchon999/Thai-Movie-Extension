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
