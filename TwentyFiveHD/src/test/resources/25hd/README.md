# Captured 25-HD DOM excerpts

Captured through a live browser on 2026-09-03 to reproduce the user's empty-homepage report.
Only relevant public DOM fragments are retained, without scripts, cookies or session values.
These are rendered DOM excerpts, not complete HTTP response captures.

- `home.html`: first two movie boxes plus Reacher boxes (including a duplicate), from https://25-hd.com/
- `search.html`: three search-result cards from a search for รีชเชอร์ on the site's search form
- `movie.html`: site banner heading, OpenGraph image, title, synopsis/tags and deferred player from https://25-hd.com/minions-monsters-2026/
- `series.html`: title and deferred player from https://25-hd.com/reacher-2022/
- `movie-poster.html` and `series-poster.html`: `.thumb-img` excerpts from those same detail pages, wrapped in their observed `.movie-description` parent class. The series OG image was also captured in version 3.

Version 3 confirms the visible posters and OpenGraph metadata use different image URLs on both
detail pages. The browser loads responsive `srcset` variants. Parser tests now prefer the visible
poster and select a bounded variant; no claim is made that all missing covers have the same cause.

The homepage had 54 `.movie_box` elements at capture time. Search paragraphs can be truncated;
the enclosing link's `aria-label` contains the full title. Movie-page header headings precede
the real title, so selector priority must be explicit rather than a CSS union with `h1`.
The real player uses `#box-player .real-player-container iframe[data-original-src]` with an empty `src`.

The ZMDB player returned a Cloudflare security block in the test browser. This fixture verifies
discovery of its declared embed URL only. It does not prove streaming extraction or playback.
