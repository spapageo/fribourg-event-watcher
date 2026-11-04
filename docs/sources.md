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

---

## eventfrog.ch — Fribourg and Bern city pages (SSR HTML + JSON-LD detail)

Verified live on 2026-11-04. Research background: `docs/research/event-sources.md` §3.2.

### Access

- Server-rendered HTML, no auth, no key. Driven through the shared Selenium `PageLoader` like the
  other HTML source; parsing still happens on the returned `getPageSource()` string, so tests never
  launch a browser.
- `robots.txt` allows `/de/events` (assumption 5 in `docs/SPEC.md` §9). The adapter stays on the
  city pages and detail pages only.
- The landing page embeds a `script#top-localities` JSON array, but it lists **city navigation
  links**, not events. The research note that suggested it carried event data is outdated; the
  adapter parses it for diagnostics only. Event data is the SSR tile markup.

### Endpoints used

| Endpoint | Notes |
|---|---|
| `GET /de/events/fribourg` | Landing page, 48 server-rendered tiles. |
| `GET /de/events/bern` | Landing page, 48 server-rendered tiles. |
| `GET /de/event-list/<city>.html?shop_recpage=N&perPage=48` | The "Mehr Events anzeigen" button's HTML fragment; same tile markup. |
| `GET /de/p/<rubric>/<subrubric>/<slug>-<id>.html` | Detail page with a schema.org `Event` JSON-LD block. Optional enrichment, bounded by the detail budget. |

### Paging budget and quirks

- A city page holds 48 tiles; the load-more fragment adds the next 48. The adapter stops when it
  has ≥50 unique events for that city, a fragment is short, a fragment repeats links, or the 60
  request cap is reached (which is a `SourceFetchException`, never silent truncation).
- Live 2026-11-04: Fribourg needed 2 requests and yielded **50** events; Bern needed 2 requests and
  yielded **~90** events inside a one-year window (the raw page carried 58 tiles in two fragments;
  events that had already started fell outside the window).
- Detail pages are one browser request each, and a Bern page can hold hundreds of events. The
  adapter therefore spends a bounded budget (30 requests by default) on the **earliest in-window
  candidates** and keeps the list-derived occurrence for the rest. That is why a later event may
  have no `end`, `description`, categories beyond its URL path, or price.
- A detail page that fails to load or has unreadable JSON-LD is logged and skipped; that event falls
  back to its list tile instead of failing the source.

### Dates without a year

- Single tiles show `Freitag, 06. November, 19:30`; ranges show
  `Samstag, 14. November, 11:00 – Sonntag, 15. November, 18:00`. The year is absent, so the adapter
  resolves each month/day against the window start as the next upcoming occurrence (a date before
  the window rolls to next year).
- Group tiles show `6 Events` with a span such as `Nov - Nov` / `6 - 15`, or `bis Dez` / `11`.
  `bis <month>` means the run ends in that month; the start is the next occurrence of the day. The
  list-derived occurrence is one all-day range from the first to the last day; a fetched group
  detail replaces it with each `subEvent`.
- `Hat bereits begonnen` carries no time. The list fallback is an all-day marker on the tile's date;
  an enriched detail page supplies the exact ongoing start/end.

### Field mapping

| Wire | `SourceEvent` | Notes |
|---|---|---|
| trailing `-<id>` of the tile/detail URL | `sourceEventId` | Stable numeric id. |
| tile title / JSON-LD `name` | `title` | Detail wins when present. |
| JSON-LD `description` | `description` | HTML → plain text, capped at 2000 chars. |
| JSON-LD `location.name` / tile location prefix | `venueName` | List fallback is everything before the last comma. |
| JSON-LD `streetAddress` + `postalCode` | `addressLine` | Joined as `"<street>, <postal>"`. |
| JSON-LD `addressLocality` / tile location suffix | `city` | Clean city name — feeds the dedup key. |
| JSON-LD `geo.latitude/longitude` | coordinates | `BigDecimal.valueOf(double)`. |
| JSON-LD `keywords` prefix / URL path segments | `categories` | e.g. `Comedy / Comedy & Kabarett` → both labels; fallback humanises `/de/p/<rubric>/<subrubric>/`. |
| JSON-LD `offers.lowPrice/highPrice/price` | `priceInfo` | `"Free entry"` when free or zero; `"CHF 25.00"` / `"CHF 25.00–30.00"` otherwise. |
| JSON-LD `eventStatus` | `status` | `EventScheduled`/`EventMovedOnline → CONFIRMED`, `EventCancelled → CANCELLED`, `EventPostponed → TENTATIVE`, else `UNKNOWN`. |
| JSON-LD `startDate`/`endDate` (or each `subEvent`) | occurrences | Exact ISO instants; start-sorted, de-duplicated. A group page yields one occurrence per showing. |
| — | `allDay` | `true` when the start is exactly local midnight (or when only a date is known). |
| — | `country` | JSON-LD `addressCountry`, otherwise `SourceEvent` defaults to `CH`. |
| — | `canton` | Not mapped; stays `null` like the other adapters. |

### Public event URL

The tile/detail URL itself: `https://eventfrog.ch/de/p/<rubric>/<subrubric>/<slug>-<id>.html`.
Group URLs use `/de/p/gruppen/...` and point at the umbrella event.

### Known gaps (v1)

- Detail enrichment is intentionally bounded by the 60-request source budget; later events keep only
  the data visible on the list tile (no end, description, price, or exact category labels).
- Year inference assumes the agenda lists upcoming events; a year-less date far in the past relative
  to the window would be read as next year.
- Only the two configured city pages are read; there is no radius/suburb expansion.
