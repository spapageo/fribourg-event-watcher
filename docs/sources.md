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

---

## bka.ch — canton Bern (agenda JSON API)

Verified live on 2026-11-04. Research background: `docs/research/event-sources.md` §3.1.

### Access

- The public React agenda (`/agenda`) fetches its data from `GET /api/events`. That endpoint is
  reachable without auth and returns the structured form of the same agenda — including times,
  place address/city/coordinates, descriptions, categories, prices and status that the rendered
  cards omit. The earlier research note "HTML only, no API" is outdated.
- The rendered `/agenda` DOM has 700 cards but no times; each `/event/<slug>` page carries
  `data-city`/`data-address`, but one detail request per event is impossible within the spec's
  60-request budget (≈2000 results). The adapter therefore reads the JSON endpoint with a plain
  `RestClient`; **bka does not use Selenium**. Operator decision recorded in `docs/SPEC.md`.
- `robots.txt` is not published (the path returns the site's 404 page), per the operator decision.

### Endpoint used

| Endpoint | Notes |
|---|---|
| `GET /api/events?limit=100&page=N&date_from=YYYY-MM-DD&date_to=YYYY-MM-DD` | Only endpoint the adapter calls. Rows are grouped by month (`data[].events[]`); `total` is page-local, not the agenda size. |

### Paging budget and quirks

- Paging stops on the first empty `data` array; a hard cap of 60 requests fails the source instead
  of truncating silently. A live 14-day window (2026-11-04 → 2026-11-18) needed **15 pages /
  ~1400 rows → 676 canonical events** (~32 s at 1 req/s), comfortably inside the cap.
- `date_from`/`date_to` are calendar days and use **overlap semantics**: an exhibition that started
  in 2025 is returned because it still runs inside the window. The adapter re-checks instant
  overlap client-side because the parameters are dates, not instants.
- Every request goes through the shared `RateLimiter` (≤1 req/s per host).
- No `last`/`next` marker exists: page 1 of a non-matching filter returns `data: []`, which is the
  normal stop condition.

### Rows are showings, not events

- A recurring production appears once per date as an `event_type: "subevent"` row carrying
  `mainevent_id` and `mainevent_slug`; the umbrella row is `event_type: "mainevent"`. Measured in
  one 14-day window: 300 rows included 54 subevents.
- The adapter groups rows by `mainevent_id` (falling back to the row's own `id`), merges, dedupes
  and start-sorts their occurrences into **one** `SourceEvent`. This keeps a run of performances as
  one canonical event with several showings; the digest still prints it once per occurrence.
- A multi-day row (`date_from`..`date_to` spanning weeks) maps to one long occurrence, like insitu,
  so `payload_hash` stays stable while the exhibition runs. A start at exactly midnight becomes
  `allDay = true`.
- `recurrence`/`recurrence_week_days` are **not** expanded: the API already returns each showing as
  its own row, and expanding weekly rules would invent showings the organiser did not publish.

### Field mapping

| Wire | `SourceEvent` | Notes |
|---|---|---|
| `mainevent_id` (or `id`) | `sourceEventId` | Stable identity for the grouped event. |
| `name` | `title` | First non-blank row of the group. |
| `place.name` (fallback `place_proposed.name`) | `venueName` | `place` is `null` for ~7% of rows; the proposed place carries the same fields. |
| `place.address` + `place.zip` | `addressLine` | Joined as `"<address>, <zip>"`. |
| `place.city` | `city` | Clean city name — feeds the dedup key. |
| `place.latitude/longitude` | coordinates | `BigDecimal.valueOf(double)`. |
| `labels.description.de` (fallback `fr`) | `description` | HTML → plain text, capped at 2000 chars. |
| `categories[].label.de` (fallback `fr`) | `categories` | Union across the group's rows, deduplicated. |
| `event_status` | `status` | `confirmed → CONFIRMED`, `canceled → CANCELLED`, `full`/`postponed` → `UNKNOWN` (the model has no sold-out state). |
| `date_from` / `date_to` | one `EventOccurrence` per row | `date_to` is never null in practice. |
| — | `allDay` | `true` when `date_from` is exactly midnight. Upstream exposes `is_multi_day_event`, but the heuristic matches the digest's existing rule. |
| `special_rate` / `rates[].price` + `labels.designation.de` | `priceInfo` | `free_entry`/`free_price` → `"Free entry"`; otherwise `"Erwachsene: CHF 13, Kinder (ab 6 Jahren): CHF 5"`. |
| — | `country` | Absent upstream; `SourceEvent` defaults it to `CH`. |
| — | `canton` | Not exposed by the agenda JSON; stays `null`. |

### Public event URL

`https://www.bka.ch/event/<mainevent_slug or slug>` — 200 directly. The adapter prefers
`mainevent_slug` so a grouped run points at the umbrella event; if no slug exists it emits
`url = null` rather than inventing a link.

### Known gaps (v1)

- No per-showing time details beyond each row's `date_from`/`date_to`; an exhibition's daily opening
  hours are not modelled (one long occurrence instead).
- Recurring weekly rules are not expanded into future showings beyond the rows upstream returns.
- No `canton` field; the whole agenda is canton Bern by definition.
