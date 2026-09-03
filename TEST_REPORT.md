# Validation — 2026-09-03

| Check | Result |
| --- | --- |
| GitHub connection | Authenticated account Banchon999 accessible; target Banchon999/Thai-Movie-Extension identified |
| Live 25-hd.com inspection | Blocked by HTTP 403; no site HTML obtained |
| Cloudstream API review | Read current upstream MainAPI, ExtractorApi, BasePlugin example and Gradle metadata source |
| Python release tests | PASS: 5 tests |
| Kotlin parser tests | 7 synthetic-fixture tests included; NOT RUN |
| Kotlin compilation | NOT RUN: no usable Gradle/Android SDK; tool download did not complete |
| CS3 / JAR generation | NOT RUN; no installable binary in this source ZIP |
| GitHub Actions run | Initial build pending on Banchon999/Thai-Movie-Extension |
| Android playback | NOT TESTED |

The Python tests verify release staging only: fork URL correctness, binary size/hash matching,
required CS3 entries, invalid repository rejection, and rejection of a source ZIP renamed to CS3.
They do not verify provider selectors, Kotlin compilation, DEX validity beyond its header, or video playback.

All HTML used by the Kotlin tests is synthetic. These fixtures are explicitly not captured from 25-hd.com.
Provider metadata remains status=3 (beta). Do not mark stable until build and live playback succeed.

Source is being submitted to Banchon999/Thai-Movie-Extension. Live-site evidence and playback verification remain outstanding.
