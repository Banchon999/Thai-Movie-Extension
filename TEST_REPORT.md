# Version 2 validation — 2026-09-03

Version 2 fixes `.movie_box` cards, full search titles, movie-heading priority, metadata and deferred iframe discovery.
Browser inspection succeeded for the homepage (54 cards), Thai search (3 results), empty search, movie and series pages.
Five captured-markup regression tests were added. Version 2 build results will be recorded after CI completes.
ZMDB returned a Cloudflare security block; playback and external TV episode enumeration remain unverified.

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
