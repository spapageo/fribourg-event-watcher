# Sources: selectors, limits, quirks

Per-source operational notes. Every adapter change updates this file (see `docs/SPEC.md` §7).

Global rules that apply to all sources (`docs/SPEC.md` §7):

- ≤1 request/second per host, ≤60 requests per source per run.
- User-Agent `fribourg-events-digest/1.0`, no contact details.
- No retries tighter than the pipeline's bounded policy; no bypassing bot protections.
- Parsers are pure functions over a `String` page source so tests never need a browser or network.

---

## insitu.live — canton Fribourg (open API)

Verified live on 2026-11-03. Research background: `docs/research/event-sources.md` §2.1.

### Access

- Open JSON-LD/Hydra API, no auth, no key. Do **not** drive it through Selenium; the front-end is
  a single-page app but the API is plain JSON.
- `robots.txt` publishes `Content-Signal: search=yes, ai-train=no, use=reference`. A personal digest
  that links every item back to the source is `use=reference`; do not train on or republish content.

### Endpoints used

| Endpoint | Notes |
|---|---|
| `GET /api/events?limit=100&page=N` | The only endpoint the adapter calls. Results are grouped by month (`member[].events[]`) with paging in `view.{first,last,next}`. |

### Paging budget and quirks

- `limit` is **ignored**: every page carries 30 rows regardless. At probe time the agenda was 1310
  rows over 44 pages, so one full crawl is 44 requests (~45 s at 1 rps) — inside the 60-request cap.
  If the agenda keeps growing, the adapter fails with `SourceFetchException` at the cap instead of
  silently truncating; raise the cap with a spec change, not ad hoc.
- `view.next` absent ⇒ last page. The crawl stops there.
- `from`/`to` query parameters are silently ignored; `date_from`/`date_to` answer **HTTP 500**.
  ⇒ the window is filtered client-side, in the adapter.
- Ordering is ascending by `next_date`, which is stable enough to read but is *not* used for
  early termination (an ongoing run legitimately sorts before the window).
- `city`/`q`/`search` filters are ignored by the API. City filtering is a client-side concern.

### Rows are open days, not events

- One event appears **once per day it is open/on**, with the same `date_from`/`date_to` run range and
  a different `next_date`. Measured: 1310 rows = **886 distinct events**; one recurring event had 81
  rows. The run range for a given `id` was identical across all its rows (0 inconsistencies).
- The adapter keeps the first row per `id` and maps the run range to a single occurrence, so a
  multi-day exhibition is one event with one long occurrence and its `payload_hash` stays stable
  while it runs. `next_date` (per-showing time) is deliberately discarded — the spec wants multi-day
  events shown once, with start + "ongoing until" (`docs/SPEC.md` §9.8).

### Field mapping

| Wire | `SourceEvent` | Notes |
|---|---|---|
| `id` | `sourceEventId` | Stable identity, preferred over `slug`. |
| `name` | `title` | As published (FR for this source). |
| `place.name` | `venueName` | `place` may be `null`. |
| `place.address` + `place.zip` | `addressLine` | Joined as `"<address>, <zip>"`. |
| `place.city` | `city` | Clean city name — feeds the dedup key. |
| `place.latitude/longitude` | coordinates | `BigDecimal.valueOf(double)`. |
| `labels.description.fr` (fallback `de`) | `description` | HTML converted to plain text, capped at 2000 chars. |
| `categories[].label.fr` (fallback `de`) | `categories` | Deduplicated. |
| `event_status` | `status` | `confirmed → CONFIRMED`, `canceled → CANCELLED`, `full → UNKNOWN` (no sold-out state in the model), anything else `UNKNOWN`. |
| `date_from` / `date_to` | one `EventOccurrence` | Run range; `date_to` is never null in practice. |
| — | `allDay` | `true` when `date_from` is exactly midnight (16/1310 rows at probe). Upstream exposes no all-day flag, so this is a heuristic. |
| — | `country` | Absent upstream; `SourceEvent` defaults it to `CH`. |
| — | `canton`, `priceInfo` | Not exposed by the listing; stay `null`. |

### Public event URL

`https://insitu.live/fr/evenement/<slug>-<id>` — returns 200 directly. The form without `-<id>`
answers 301 to it, and the SPA route `/<slug>/evenement/fr` from the front-end routing bundle is 404,
so build the URL with the id suffix. If `slug` is missing the adapter emits `url = null` rather than
inventing a link.

### Known gaps (v1)

- No per-showing times: the documented detail routes (`/api/v2/events/<slug>`, `/api/v2/events/<id>`)
  return 404, and the SSR event page carries no date list. Revisit if insitu publishes the v2
  outgoing-API docs (`support@insitu.live`).
- No `canton` or price information in the agenda listing.
- Success criterion 2 in `docs/SPEC.md` §8 says "≥1000 upcoming events"; the agenda currently yields
  886 distinct events (1310 open-day rows). Recorded as a spec/reality gap, not worked around.
