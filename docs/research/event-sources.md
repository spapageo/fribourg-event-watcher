# Event sources: Fribourg & Bern (CH)

Research date: 2026-02 · Method: web search + live HTTP probing of candidate sites
(all status codes below were measured from this host, follow-redirects on,
desktop browser UA; a few `403`/`000` are bot-blocks, not dead sites).

---

## TL;DR

| Goal | Best source |
|---|---|
| **All events in canton/city Fribourg, machine-readable** | `insitu.live` — **open JSON-LD/Hydra API, no auth** ✅ verified |
| **Curated, authoritative Bern city/canton events** | `bka.ch` (Berner Kulturagenda) — HTML only, no API |
| **Commercial breadth, both cities** | `eventfrog.ch/de/events/{bern,fribourg}` — SSR HTML, API for partners |
| **Bern → structured data** | `hinto.ch` OLE (Open Linked Event Data) XML |
| **Museums Bern** | `mqb.ch/de/agenda` (JSON-LD present) |

Key asymmetry: **Fribourg has a cantonal open event API; Bern has none.**
Bern's de-facto standard (`bka.ch`) is curated and publicly subsidised but
publishes no feed. Plan for Fribourg = API ingestion, Bern = HTML/OLE scraping.

---

## 1. Cross-city / pan-Swiss aggregators

| Source | URL | Status | Data access |
|---|---|---|---|
| Eventfrog (city pages) | `eventfrog.ch/de/events/bern`, `/fribourg` | 200 | SSR HTML (~63 event links/page), embedded `top-localities` JSON; API `/api` exists, partner docs |
| Localcities | `localcities.ch/de/veranstaltungen/bern/801` | 200 | JS-rendered, no JSON-LD in head → needs browser or internal API |
| Schweiz Tourismus | `myswitzerland.com/.../veranstaltungen-suche/-/bern` | 403 | Bot-blocked; Guidle/discover.swiss data behind paid API |
| eventkalender.ch | `eventkalender.ch/bern/` | 200 | Classic server-rendered HTML, filters `?jahr&monat&tag&kat&kanton` |
| eventswiss.ch | `eventswiss.ch/de/evenements/bern` (+`/berne`) | 200 | HTML, date/category filters |
| gemeindekalender.ch | `gemeindekalender.ch/event_liste.cfm?key=208&gemeinde=Bern` | 200 | Old HTML endpoint; good for small/associational events |
| portal724.ch | `portal724.ch/Events/Ort/Bern/ID/29956` | 200 | HTML, search by place/PLZ/category |
| CULTURALL / kultur-bern.ch | `culturall.info`, `kultur-bern.ch` | 200 | Covers **Bern, Solothurn, Biel, Thun, Fribourg, Neuchâtel**; ~76 RSS links on homepage (per-category feeds) — worth mining |
| Open Event Data (association) | `openeventdata.ch` | 200 | Directory of ~40 open interfaces (OLE); JS-rendered, principle: CC0 factual event data, no auth |
| Ticketmaster | `ticketmaster.ch/discover/bern` | 200 | Ticketing only (concerts/shows) |
| Ticketcorner | `ticketcorner.ch` | 000 | Connection blocked from this host; site is alive |

Pan-Swiss ticketing note: **Petzi** (`petzi.ch`, 200) is the reference for club/indie
concerts in both cities, but it publishes **no public API/feed** (only partner
integrations) — treat as scraping target per ToS.

---

## 2. Fribourg

### 2.1 Aggregators — the important ones

**In Situ — `insitu.live` — ✅ open API (main finding)**
- Cantonal bilingual (FR/DE) cultural platform since 2023, operated by Association In Situ.
  Supersedes `in-situ.org`. Front-end is an Ember SPA (`/build/app.*.js`), so **HTML scraping is useless** —
  but the SPA talks to a public, unauthenticated JSON-LD (Hydra) API:
  ```
  GET https://insitu.live/api/events?limit=100&page=1
  GET https://insitu.live/api/events/count          → {"totalItems": 1307}
  GET https://insitu.live/api/places?limit=200      → {"totalItems": 622}
  GET https://insitu.live/api/organizations
  ```
  Verified response shape (`/api/events`): grouped **by month**, e.g.
  `member[0] = {"date":"2026_11","events":[{ "@id":"/api/v2/events/<slug>", "id":10678,
  "name":..., "event_status":"confirmed", "place":{"name","address","zip","city",
  "latitude","longitude"}, ... }]}`, plus `view.first/last/next` paging
  (100/page, 44 pages at time of check for the agenda view).
- Coverage: whole canton — place cities seen: Fribourg (198 places), Bulle (76),
  Estavayer (22), Villars-sur-Glâne (16), Romont (15), Gruyères (15), Charmey (15),
  Châtel-Saint-Denis (13), Murten (11), Gibloux (9).
- Filtering: `limit`/`page` work; `city=`, `q=`, `search=` are silently ignored
  (same 1307 total) → **filter client-side**, or reverse-engineer the param names
  from `app.*.js` (`/api/v2/events/agenda{?}` IRI template was returned but with an
  empty `mapping` array). Ask `support@insitu.live` for the v2 outgoing-API docs.
- Licensing: platform is going open source (planned 2026); API access is
  partnership-based ("API sortante" for media/diffusion partners). Verify terms before
  redistributing, but there is no auth wall.

**Ville de Fribourg — `ville-fribourg.ch/agenda`** (200)
- Official city agenda + monthly "Memento" (PDF). RSS present in page head but it is
  `actualites.rss` (news, not events). Server-rendered HTML; the city also advertises
  an iCal/Google Calendar subscription for the agenda — not yet verified.
- `ville-fribourg.ch/memo/activites-culturelles` is the culture landing page.

**Fribourg Région / Tourisme — `fribourg.ch/fr/evenements`** (200)
- Tourism calendar for the whole canton, filters by date/type.

**Agglomération de Fribourg — `agglo-fr.ch/promotion-culturelle/agendas-culturels`** (200)
- Monthly selection of subsidised cultural associations; complements In Situ.

### 2.2 Media agendas
| Source | URL | Status |
|---|---|---|
| La Liberté | `laliberte.ch/agenda` | 200 |
| La Gruyère | `lagruyere.ch/agenda` | 200 (filters `dateFrom/dateTo/regionIds`) |
| Frapp / RadioFr | `frapp.ch/fr/agenda` | 200 |
| La Télé ("On sort") | `latele.ch/emissions/on-sort-fr` | 200 (weekly editorial selection) |

### 2.3 Venues & institutions (primary sources, often ahead of agendas)
`equilibre-nuithonie.ch` 200 · `fri-son.ch` 200 · `nouveaumonde.ch/agenda/` 200 ·
`nof.ch` (Nouvel Opéra) 200 · `laspirale.ch` 200 · `kellerpoche.ch` 200 ·
`badbonn.ch` (Düdingen) 200 · `belluard.ch` 200 · `theaterinfreiburg.ch` ·
`bilboquet.ch` **000** (unreachable from here, verify manually).
Festivals: `fiff.ch` 200 · `fims-fribourg.ch` 200 · Belluard Bollwerk · Friscènes ·
Rencontres de folklore internationales `rfi.ch` · Nuit des Musées.
Region: L'Azimut (Estavayer), CO2 & Ebullition (Bulle), Théâtre des Osses (Givisiez),
Vitromusée (Romont), Musée de Charmey.

### 2.4 Institutional
`fr.ch` — Service de la culture (canton). Note: `fr.ch/culture` and `fr.ch/fr/culture`
both **404** → use the site search / `fr.ch` → DFAC, don't hardcode the path.

---

## 3. Bern

### 3.1 Berner Kulturagenda (BKa) — `bka.ch` (200, the de-facto standard)
- Curated, editorial (not a dump), ~250 member organisers, print edition as a
  supplement in Der Bund / BZ / Berner Oberländer / Thuner Tagblatt.
- **City-subsidised**: Gemeinderat approved a 2024–2027 Leistungsvertrag of
  CHF 75'000/yr for a "comprehensive digital cultural event calendar"; the political
  requirement is explicitly *curated* content, not a raw aggregator.
- Organisers self-enter events at `admin.bka.ch` (guideline: ≥5 weeks ahead).
- **Data access: HTML only.** Event pages (e.g. `/event/noches-de-la-tribu`) carry
  `data-city="Bern"`, `data-address="..."` attributes but **no JSON-LD, no .ics, no
  documented API**. This is the single biggest source for Bern and must be scraped.

### 3.2 Structured / semi-structured
| Source | URL | Status | Access |
|---|---|---|---|
| Hinto | `hinto.ch` → `hinto.net/de/events-von-a-bis-z-94.html` | 200 (note: `/de/agenda` 404s) | **JSON-LD present** on agenda page; publishes **OLE** (Open Linked Event Data, XML, CC BY-SA spec, CC0 content, free WordPress plugin + `composer require fugugmbh/oledata`) |
| Museumsquartier Bern | `mqb.ch/de/agenda` | 200 | JSON-LD; joint agenda of Kunsthalle, Hist./Naturhist. Museum, ALPS, Museum für Kommunikation, NB |
| Bühnen Bern | `buehnenbern.ch/spielplan/programm/` | 200 | Has `?ics=` export params |
| Kursaal | `kursaal-bern.ch/erleben/event-kalender` | 200 | TYPO3 eventcalendar, paged |
| BERNEXPO | `bernexpo.ch` (Kalender) | 200 | Fairs/congress |
| Sportamt Stadt Bern | `sportamt-bern.ch/.../veranstaltungskalender/` | 403 | Sport events; bot-blocked |
| Bern Welcome / `bern.com` | `bern.com/de/veranstaltungen` | **403** | Official tourism calendar (Guidle → discover.swiss); free browsing, **paid API** (~CHF 2k setup) |
| Localcities Bern | `localcities.ch/de/veranstaltungen/bern/801` | 200 | JS, thousands of entries incl. neighbourhood events |

### 3.3 Media
`derbund.ch` (302 → paywall/consent) · `bernerzeitung.ch` · `journal-b.ch` 200 ·
`hauptstadt.be` 200 (root is 200, `/agenda` 404 — use the site nav) ·
`baerntoday.ch` **000** (CH Media; blocked from this host) · `baernerbaer.ch` 200
(monthly "Event-Highlights" roundups) · `leporello.ch` 200 (family/kids culture).

### 3.4 Venues
`dampfzentrale.ch` 200 · `schlachthaus.ch` 200 · `dachstock.ch` 200 · `progr.ch` 200 ·
`bierhuebeli.ch` 200 · `zpk.org` 200 · `kunstmuseumbern.ch` 200 · `stattland.ch` 200
(city tours) · `buskersbern.ch` 200 · `mahoganyhall.ch` **000** (verify).

### 3.5 What does *not* exist
- **No event data in Bern open data.** Stadt Bern OGD (~45 datasets) and Statistik
  Stadt Bern (~66) are geometric/statistical (playgrounds, markets, cemeteries);
  Kanton Bern OGD strategy 2024–2028 is still maturing, geodata only.
- No official city event feed at `bern.ch` (`bern.ch/agenda` → 404); the city points
  to partners.

---

## 4. Machine-readability summary

| Tier | Sources |
|---|---|
| **Open API, no auth** | `insitu.live/api/*` (Fribourg, ~1300 upcoming events) |
| **Open standard (OLE XML)** | Hinto network members (Bern-heavy), see `openeventdata.ch` |
| **JSON-LD / schema.org Event in page** | `hinto.net`, `mqb.ch` |
| **RSS/Atom/ICS** | `kultur-bern.ch` (per-category RSS), `buehnenbern.ch` (`?ics=`), `ville-fribourg.ch` (news RSS only) |
| **Server-rendered HTML, stable URL patterns** | `eventfrog.ch/de/events/{bern,fribourg}`, `bka.ch`, `eventkalender.ch`, `lagruyere.ch/agenda`, `eventswiss.ch`, `gemeindekalender.ch`, `portal724.ch` |
| **JS-only → needs headless browser** | `localcities.ch`, `insitu.live` front-end (API is the way), `openeventdata.ch`, `bern.com` |

---

## 5. Ingestion recipes

**Fribourg (recommended primary)**
```bash
# 44 pages × 100 events, grouped by month
for p in $(seq 1 44); do
  curl -s "https://insitu.live/api/events?limit=100&page=$p" -o "insitu-$p.json"
done
# → flatten member[].events[], keep: id, slug, name, event_status,
#   place.city, place.address, place.latitude/longitude, dates
```
Filter to Fribourg city client-side via `place.city == "Fribourg"` (198 places).

**Bern (no API — pick two of these)**
1. `eventfrog.ch/de/events/bern` — SSR list, ~63 links/page, then parse event pages.
2. `hinto.net` — prefer OLE XML if the specific organiser publishes it; JSON-LD as fallback.
3. `bka.ch` — scrape `/agenda` + `/event/<slug>` (highest editorial value, lowest volume).

**Deduplication warning**: `insitu.live` is upstream of several Fribourg outlets
(agglo-fr, some media agendas); Hinto is upstream of many Bern venue sites. Dedup on
`(title, start, venue)` before merging, or you will count events 2–3×.

---

## 6. Open questions / next steps
1. Get **In Situ API v2 docs** (`support@insitu.live`) — confirms filter params, rate
   limits, attribution rules, and whether bulk export is allowed.
2. Confirm `ville-fribourg.ch/agenda` **iCal feed** exists (city advertises calendar
   subscription) — if yes, it's the cleanest *city-scope* Fribourg source.
3. Ask BKa (`redaktion@bka.ch`) whether members' data is available as OLE/JSON —
   they are city-funded, so a data-portability request is reasonable.
4. Re-probe the `000`/`403` hosts (`baerntoday.ch`, `bilboquet.ch`, `ticketcorner.ch`,
   `mahoganyhall.ch`, `bern.com`, `sportamt-bern.ch`, `myswitzerland.com`) from a
   normal residential connection before writing them off.
