# ARCHITECTURE.md — Plate (software-level)

How this codebase is organized and why. Suite-level context: `C:\Code\ARCHITECTURE.md`. Working
instructions + closed decisions: [CLAUDE.md](CLAUDE.md). Backlog: [ROADMAP.md](ROADMAP.md).

Plate is the most feature-complete app in the suite and the **hub of the cross-app roadmap**
(nutrition is the shared language between Spotter and Cookbook). When a convention is ambiguous,
match Spotter (the reference implementation).

## System shape

```
Android (Kotlin/Compose) ⇄ FastAPI :8001 ⇄ Postgres :5433
                                │
                                ├→ LM Studio :1234 (coach chat + photo-a-meal vision)
                                ├→ USDA FoodData Central + Open Food Facts (cached external food data)
                                └→ Spotter GET /workouts?date= (training-day awareness)
Provides to Cookbook: GET /recipes/export, POST /cross-app/resolve-foods, POST /cross-app/log-recipe
Provides to the suite: GET /cross-app/remaining?date= (fits-today badge), GET /cross-app/summary?start=&end= (weekly digest)
```

## Server (`server/`)

### Layers

Standard suite layering (`routers/` → `services/` → `models/`, Pydantic `schemas/` at the
boundary) plus **two pure domain packages** that are the heart of the app:

- **`app/nutrition/`** — ALL macro math: `constants.py`, `portions.py`, `targets.py`
  (Mifflin-St Jeor TDEE → goal adjustment → protein from bodyweight → fat floor → carbs fill,
  training-day bump; `compute_targets` takes an optional `maintenance_override`), `totals.py`,
  `trend.py` (observed weight rate, `classify_pace`), `adaptive.py` (adaptive-TDEE correction:
  energy-balance solve of observed maintenance from intake vs weight change, confidence blend +
  deviation clamp — ROADMAP2 T3 #1), `units.py` (metric-canonical ↔ imperial-display conversion).
  Pure functions, table-driven tests, no I/O. **Clients display, never compute targets** — if a
  number is wrong on the phone, the bug is here or in what feeds it, not in Kotlin.
- **`app/foods/`** — external food data: `base.py` (provider seam), `usda.py`, `off.py`
  (Open Food Facts), `normalize.py`. Resolution order is **local `foods` cache → USDA → OFF**;
  barcodes go straight to OFF then cache. Rows are cached forever on first fetch (staleness TTL
  is a known roadmap item). Never live-hit providers per keystroke; never ship API keys in the APK.

`app/recipes_ext/` (Spoonacular discovery) also lives here; its canonical home moved to Cookbook —
keep the two in sync only when the shared seam changes.

### Domain map

| Domain | Router | Service | Models |
|---|---|---|---|
| Auth/users | `auth.py`, `users.py`, `suite_auth.py` | `auth_service`, `user_service`, `suite_auth` | `User` (settings JSON holds `unit_system`) |
| Food catalog + search | `foods.py` | `food_service` | `Food` |
| Diary | `log.py` | `log_service` | `FoodLogEntry` |
| Goals/targets | `goals.py` (`/targets`, `/adaptive`) | `goal_service`, `adaptive_service` (+ `nutrition/targets`, `nutrition/adaptive`) | `UserGoal`, `DailyTarget` |
| Bodyweight | `metrics.py` | `metric_service` (+ `nutrition/trend`) | `BodyMetric` |
| Recipes/saved meals | `recipes.py` | `recipe_service`, `recipe_discovery_service` | `Recipe`, `RecipeItem` |
| Restaurants (chain templates) | `restaurants.py` | `restaurant_service`, `menu_fetch`, `services/ai/menu` | `Restaurant`, `RestaurantComponent` |
| AI coach + photo | `ai.py` | `services/ai/` | writes via log/recipe services only |
| Cross-app provider | `cross_app.py` (+ `/recipes/export` in `recipes.py`) | `cross_app_food_service` | — |
| Spotter consumer | — | `workout_source.py` | — |
| Export | `export.py` | `export_service` | generic dump |

### AI (`app/services/ai/`)

Mirrors Spotter's guardrail contract exactly (server-side prompts, untrusted user chat, Pydantic-
validated structured output, no tool access). Two surfaces:
- **Coach chat** — system prompt carries trusted DB context: remaining macros today, goal/pace,
  and Spotter's training status via `workout_source` (silently absent when the flag is unset).
- **Photo-a-meal vision** — strict-JSON prompt to the LM Studio multimodal model; the result is
  an **editable draft the user confirms** — never auto-logged. Low confidence → prompt the user
  to refine/search. This pipeline seeded Cookbook's photo import; improvements here should be
  offered there too (and vice versa — don't let the forks drift).
- **Nutrition-label scan** (`POST /foods/label`) — the same vision seam with a label-specific
  prompt that *transcribes* a Nutrition Facts panel (one food = one serving) rather than
  estimating a meal. Reuses `parse_estimate` + the `PhotoEstimateResponse` draft shape and the
  never-auto-committed guarantee; higher accuracy than a meal photo.
- **Voice logging** (`POST /foods/voice`) — speech→text is done **on-device** on the client (no
  audio leaves the phone); the server takes only the text, runs a structured LM Studio parse
  (`voice_prompts` → `{food, quantity, unit}`, Pydantic-shaped, forgiving parser), resolves each
  spoken food against the **trusted food search** for real macros, and returns the same editable
  `PhotoEstimateResponse` draft (unresolved foods kept as low-confidence stubs). Never auto-logged.
- **Menu parsing** (`POST /restaurants/parse-menu`) — the voice pattern applied to a restaurant
  menu from **exactly one of** `url` (fetched) or `text` (pasted menu/nutrition text, no fetch —
  the robust path when a chain's nutrition lives on a JS page the server can't fetch). For a URL,
  `services/menu_fetch.py` (plain IO, deliberately outside `ai/`) fetches it; then
  `menu_prompts`/`menu.py` structure the text into categorized components. Nutrition stated on
  the page is carried **verbatim** (`official` block, minted into a `Food(brand=<restaurant>)` on
  save); otherwise a generic `search_term` resolves against trusted search (estimate). The parse
  is a draft response only — nothing persists until the client POSTs `/restaurants`. **Fetch
  policy:** http(s) only, SSRF guard rejecting private/loopback/link-local hosts re-run per
  redirect hop (`MENU_FETCH_BLOCK_PRIVATE_IPS=false` opts a homelab out; DNS-rebinding between
  check and fetch is accepted residual risk for a personal deploy), 5 MB streaming cap, 15 s
  timeout, HTML via a stdlib tag-stripper, PDFs via pypdf (an image-only PDF 422s toward the
  manual builder). Rate limit 5/min — each call is an outbound fetch plus an LM completion.

### Cross-app auth (both directions)

`CROSS_APP_SECRET`-signed HS256 JWTs carrying the user's **email** (the only stable cross-app
identity), separate surface from session auth, disabled (401) when unset. RS256 service tokens
from dragonfly-id are shipped-but-dormant: providers dual-accept RS256 (via the same JWKS as SSO)
then legacy HS256; `services/cross_app_token.fetch_cross_app_token` mints RS256 when
`CROSS_APP_CLIENT_ID/SECRET` are set. See `Dragonfly/CROSS-APP.md` for activation status.

### Migrations & tests

Alembic (6 revisions, `0001`–`0006`; `0005` back-fills the goal-rate sign invariant onto existing
rows, `0006` adds `restaurants`/`restaurant_components` — components keep their own display
`name` and a `food_id` SET NULL link, the `recipe_items` semantics, plus a `shared` flag:
shared restaurants are readable/loggable by **every account on the server** (log entries land
under the caller), while edit/replace/delete stay owner-only — a chain's menu is shared data,
the household exception to the otherwise strict per-user isolation). **`default_checked` is the
owner's private "usual order" pre-tick config, not shared menu structure: `_to_out` surfaces it
only to the owner (`False` for other viewers), so one account's pre-ticks never show up
pre-checked on another's log sheet.** Migrate-on-boot. 35 pytest files; CI also runs
`ruff format --check` (run it locally before pushing). Local recipe (CLAUDE.md "Testing"):
throwaway Postgres container, `DATABASE_URL` on **127.0.0.1**, `DB_NULLPOOL` — the live
`server/.env` DB password is deliberately stale for pytest purposes. One env-dependent local-only
failure (`test_discover_disabled_without_key`) when real API keys are present; green in CI.

## Android (`android/`, package `com.plate`)

Standard suite MVVM (`ui/` → ViewModel → `data/repository/` → Room `data/local/db` + Retrofit
`data/remote/`). Feature packages:

- `ui/diary/` — the daily food log (Breakfast/Lunch/Dinner/Snacks), the core surface.
- `ui/food/` — search (cache-first), portion entry (g and oz both accepted).
- `ui/scan/` — ML Kit barcode → OFF lookup.
- `ui/photo/` — photo-a-meal capture → server estimate → **editable confirm screen** (the draft
  contract, client side). The same screen serves the **nutrition-label scan** via a `labelMode`
  flag (label endpoint + label copy); the draft editor (`EstimateList`) is reused by voice too.
- `ui/voice/` — voice logging: on-device `RecognizerIntent` speech→text (offline preferred) →
  `PhotoLogViewModel.analyzeVoice` → `/foods/voice` → the shared draft editor. Entry points for
  photo / label / voice all live on the food-search top bar.
- `ui/restaurant/` — chain checkbox templates ("I ate a Salsa Grille bowl"): list (own + shared,
  one-tap import of the bundled `assets/restaurant_presets.json` chain presets — official
  nutrition transcribed at authoring time, `PresetParser` pure + tested against the real asset),
  editor (menu-link parse merged as an editable draft + embedded food search; nothing hits the
  server until Save), and the **log sheet** — category-grouped checkboxes pre-ticked from
  per-component defaults, portion overrides, running totals (display-only; the server recomputes)
  → `POST /restaurants/{id}/log`. Entry points: the food-search top bar and the Recipes screen
  (no sixth bottom tab).
- `ui/coach/` — AI coach chat.
- `ui/metabolism/` — the adaptive-TDEE "metabolism dashboard" (`MetabolismScreen`/
  `MetabolismViewModel`), opened by tapping the Home metabolism card: presents observed
  maintenance, why targets moved, and confidence (MacroFactor-style) over the `/goals/adaptive`
  read; the pre-unlock learning state has its own screenshot baselines.
- `widget/` — a Glance home-screen app widget (`PlateWidget`) showing today's remaining macros,
  fed by `data/local/SnapshotStore` that the diary/home VMs stamp on change
  (`WidgetSnapshotWriter`/`WidgetRefresher`). Read-only mirror; never a write path.
- `ui/goals/`, `ui/home/`, `ui/calendar/` — targets, dashboard (rings/remaining), history.
- `util/Units.kt` — the client half of metric-canonical/imperial-display; display defaults
  imperial (lb/oz) with a lb↔kg toggle persisted in `users.settings`.
- `util/nudges/` — opt-in retention nudges (client-only, no server): two meal reminders +
  an evening "nothing logged today" nudge, scheduled with **inexact** AlarmManager alarms that
  self-reschedule (`NudgeScheduler`/`NudgeReceiver`; `NudgeBootReceiver` restores them after a
  reboot). Pure scheduling/quiet-hours logic lives in `NudgeLogic`. The opt-in flag + quiet hours
  live in `AppPreferences`; `LogRepositoryImpl` stamps the last-logged day so the evening nudge
  can skip itself. Gated on the runtime notifications permission.
- `ui/theme/PlateTheme.kt` — Pulse semantics: Plate **leads green**; nutrition channels
  protein/carbs/fat/calories; its own emerald hero gradient (not the library's accent gradient).

### Offline model (2026-07-17 — supersedes the old "online-first, no sync-queue" stance)

With the backend unreachable, Plate opens, renders its core read surfaces from caches with an
honest banner, and queues its two entry-anywhere writes. The rule that decides everything:
**only `IOException` (server unreachable) degrades; `retrofit2.HttpException` (a reachable
server rejecting the request) always errors** — a rejection is never cached over or queued.

- **Startup gate** — `Routes.GATE` is the nav start destination; `GateViewModel`
  (`ui/navigation/PlateNavHost.kt`, the Cookbook pattern) resolves purely from the persisted
  `TokenStore` token, so a cached session lands on Home with zero network. Auth screens are
  unchanged; expired tokens are handled where they always were (the 401 refresh/logout path).
- **Write queues (write-through + drain, the Spotter pattern)** — two, both in Room:
  1. diary **quick-adds** (`pending_quick_add`, pre-existing) and
  2. **weigh-ins** (`body_metrics` rows with `syncPending`; `MetricRepositoryImpl` inserts the
     local row first — converted to **canonical kg before it persists**, and drained in explicit
     kg, so a lb↔kg toggle can never corrupt a queued entry — then best-effort POSTs and
     "promotes" the row to its server id on ack). An offline weigh-in *succeeds silently*.
  Draining runs on reconnect (`NetworkSyncObserver`), on Home load (`metricRepository.sync()`),
  and on every diary read (`syncPending()` inside `getDay`).
- **Read caches** — two shapes, both server-truth mirrors:
  - `cached_day` (pre-existing) now stamps `cachedAtMs`; `LogRepository.getDayStale` surfaces it.
  - `blob_cache` (key → JSON + `cachedAtMs`) behind `data/repository/Stale.kt`'s `BlobCache`
    read-through helper, applied to: goal (`"goal"`), weight trend (`"weight_trend"`), the
    **default-window** weekly summary (`"weekly_summary"`; parameterized ranges like calendar
    months stay online-only), and recent foods (`"recent_foods"`).
  A cache-served read returns `Stale(value, asOfMs)`; fresh reads carry `asOfMs = null`.
- **Stale banner idiom** — Home and Diary render Pulse's `StaleBanner(asOfMs, channel)` (fat =
  Plate's attention channel) when any feeding read was cache-served, showing the **oldest**
  `asOfMs` among stale sources — the banner reports the least-fresh data on screen.
- **Deliberately online-only** — food search, barcode lookup, photo/label/voice drafts, coach
  chat, food-by-id + recipe logging (server-side portion scaling), calendar month pages, goal
  writes. These need the server to mean anything; offline they fail fast with the uniform
  "Can't reach the Plate server" copy (`util/ErrorMessages.kt::userMessage`) instead of raw
  transport messages.
- Room is at **schema v3** (`MIGRATION_2_3`, additive — the offline queues must survive
  upgrades; `fallbackToDestructiveMigration` remains a backstop only).

Suite plumbing (same as siblings): `SuiteAuthManager` (AppAuth, client id `plate`),
`SuiteConfigReader` (hub config broker), suite-signed releases, manifest AppAuth theme override
(load-bearing).

## Invariants

1. **Metric canonical, imperial display.** Storage/wire is kg/g; conversion only at the edges
   (`nutrition/units.py` server, `util/Units.kt` client). Never store a pound.
2. **Targets are server-computed.** The client renders `DailyTarget`; it never re-derives.
3. **Photo/AI numbers are always user-confirmed estimates** — no auto-commit, anywhere.
4. **Third macro is carbs**; micronutrients are secondary fields, not a product direction.
5. External food data is cached, attributed (Settings → About), rate-limited.
6. Breaking a cross-app provider surface (`/recipes/export`, `/cross-app/*`) needs a coordinated
   Cookbook release in the same window.

## Where to make common changes

- **Target/trend logic**: `app/nutrition/` + table-driven tests. Touch nothing else first.
- **New food source or search behavior**: `app/foods/` behind the provider seam.
- **Coach context**: `services/ai/` prompt/context modules only; guardrail tests with mocked LLM.
- **New cross-app surface**: follow `routers/cross_app.py` (flag-gated, email-resolved, its own
  auth dependency, contract fixture committed under `server/tests/contracts/`).
