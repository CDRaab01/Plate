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
```

## Server (`server/`)

### Layers

Standard suite layering (`routers/` → `services/` → `models/`, Pydantic `schemas/` at the
boundary) plus **two pure domain packages** that are the heart of the app:

- **`app/nutrition/`** — ALL macro math: `constants.py`, `portions.py`, `targets.py`
  (Mifflin-St Jeor TDEE → goal adjustment → protein from bodyweight → fat floor → carbs fill,
  training-day bump), `totals.py`, `trend.py` (observed weight rate, `classify_pace`),
  `units.py` (metric-canonical ↔ imperial-display conversion). Pure functions, table-driven
  tests, no I/O. **Clients display, never compute targets** — if a number is wrong on the phone,
  the bug is here or in what feeds it, not in Kotlin.
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
| Goals/targets | `goals.py` | `goal_service` (+ `nutrition/targets`) | `UserGoal`, `DailyTarget` |
| Bodyweight | `metrics.py` | `metric_service` (+ `nutrition/trend`) | `BodyMetric` |
| Recipes/saved meals | `recipes.py` | `recipe_service`, `recipe_discovery_service` | `Recipe`, `RecipeItem` |
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

### Cross-app auth (both directions)

`CROSS_APP_SECRET`-signed HS256 JWTs carrying the user's **email** (the only stable cross-app
identity), separate surface from session auth, disabled (401) when unset. RS256 service tokens
from dragonfly-id are shipped-but-dormant: providers dual-accept RS256 (via the same JWKS as SSO)
then legacy HS256; `services/cross_app_token.fetch_cross_app_token` mints RS256 when
`CROSS_APP_CLIENT_ID/SECRET` are set. See `Dragonfly/CROSS-APP.md` for activation status.

### Migrations & tests

Alembic (3 consolidated revisions), migrate-on-boot. 24 pytest files; CI also runs
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
  contract, client side).
- `ui/coach/` — AI coach chat.
- `ui/goals/`, `ui/home/`, `ui/calendar/` — targets, dashboard (rings/remaining), history.
- `util/Units.kt` — the client half of metric-canonical/imperial-display; display defaults
  imperial (lb/oz) with a lb↔kg toggle persisted in `users.settings`.
- `ui/theme/PlateTheme.kt` — Pulse semantics: Plate **leads green**; nutrition channels
  protein/carbs/fat/calories; its own emerald hero gradient (not the library's accent gradient).

Offline: Plate is online-first (a food diary without search/targets is of limited use offline);
Room caches for reads. No sync-queue ambition here — that's Spotter's and Cookbook's problem
domain.

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
