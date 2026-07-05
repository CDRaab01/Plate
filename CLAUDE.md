# CLAUDE.md — Plate

Calorie & macro tracker with an on-device AI coach — sister app to
[Spotter](https://github.com/CDRaab01/Spotter), same stack and conventions (Kotlin/Compose
client + FastAPI/Postgres server + LM Studio for local inference). **v1 is built, deployed, and
in daily use** at https://plate.dragonflymedia.org (host ports 8001/5433). This file is the
as-built guide; the original phase-by-phase build spec this repo was grown from is preserved in
git history (this file, pre-2026-07-03).

> **Suite context:** Plate is one of five apps in the personal suite. Suite-wide architecture —
> shared signing key, release automation, the Dragonfly hub, SSO — is documented in the
> **Dragonfly repo** (`CLAUDE.md` + `BROKER.md`); Plate's ends of it are summarized in
> "Suite membership" below. When a decision here is ambiguous, **match Spotter** — consistency
> across the suite is a hard requirement, and Spotter is the reference implementation.

## What it does

Food logging across Breakfast/Lunch/Dinner/Snacks; food search backed by USDA FoodData Central +
Open Food Facts (local-cache-first: `foods` table → USDA → OFF; barcodes straight to OFF then
cached); ML Kit barcode scanning; photo-a-meal macro estimation via the LM Studio vision model
(always an editable draft — **never auto-committed**); an AI coach whose system prompt carries
remaining macros, goal, and today's Spotter training status; recipes/saved meals, quick-add,
weekly summaries; bodyweight tracking with trend/target support; daily targets computed
server-side (Mifflin-St Jeor TDEE, goal adjustment, protein from bodyweight, fat floor, carbs
fill) with a **training-day bump** when Spotter reports a workout.

## Decisions that were once open and are now closed (do not relitigate)

- **Spotter integration = read-only HTTP endpoint, NOT a shared backend.** The old §8 "plan of
  record: shared backend/users table" was rejected. Plate calls Spotter's `GET /workouts?date=`
  with a `CROSS_APP_SECRET`-signed JWT carrying the user's **email** (the only stable cross-app
  identity). Every app in the suite keeps its own backend + users table; email links accounts.
- **Units: imperial by default, metric canonical.** The user is US-based: bodyweight displays in
  **lb**, food in **oz**, with a lb↔kg toggle (`unit_system` in `users.settings`, default
  `imperial`; new/NULL-settings users are imperial). Internally everything is stored metric
  (kg/g) so the pure `app/nutrition/` engine never changes; conversion happens only at the
  API/UI edges (`app/nutrition/units.py` server-side, `util/Units.kt` client-side). Food logs
  accept both g and oz.
- **Macro math lives in `app/nutrition/` only** (constants, portions, targets, totals, trend,
  units) — pure functions, table-driven tests. Clients display, never compute targets.
- **Third macro is carbs**; cholesterol/fiber/sugar/sodium/sat-fat are secondary fields.
- **No custom CV training.** Photo→macros is the LM Studio multimodal model with a strict
  JSON-only prompt; low confidence prompts the user to refine or search manually.

## Cross-app surfaces (Plate is both a consumer and a provider)

- **Consumes (Spotter-awareness):** `SPOTTER_BASE_URL` + `CROSS_APP_SECRET` in `server/.env`
  enable the training-day targets bump and the coach's "user trained today" framing.
- **Provides (for Cookbook):** `routers/cross_app.py` — `GET /recipes/export`,
  `POST /cross-app/resolve-foods`, `POST /cross-app/log-recipe`. Same auth model: cross-app JWT
  signed with the **one suite-wide `CROSS_APP_SECRET`** (shared identical value across Spotter/
  Plate/Cookbook `.env`s — rotate everywhere at once), email-resolved user, disabled (401) when
  the secret is unset. Cookbook uses these to import Plate recipes and log cooked meals back
  into the food diary.

## Suite membership (Dragonfly hub / SSO / releases)

Same three integrations as Spotter — see Spotter's CLAUDE.md "Suite membership" for the full
prose; Plate's specifics:

- **Releases:** `release.yml` publishes a suite-key-signed APK + `version.json` on any
  `android/**` push to `main`; `apksigner` guard pins the suite signer (`5a596c9e…`).
  versionCode = epoch minutes (local debug builds can't install over CI releases — uninstall
  first). Deploys: push → CI → self-hosted runner `plate` (a Windows service) runs
  `deploy/redeploy.ps1` against `vars.PLATE_DIR`.
- **Config broker:** `util/SuiteConfigReader` reads
  `content://com.dragonfly.suiteconfig/config/plate` in `App.onCreate` (process restart needed
  to pick up changes), falls back to local prefs without the hub.
- **SSO (live):** server `POST /auth/suite` (`services/suite_auth.py`, JWKS validated against
  https://id.dragonflymedia.org, find-or-create **by email**, flag = `SUITE_JWKS_URL` +
  `SUITE_ISSUER` **pinned in `docker-compose.yml` `environment:`** — env_file-only flags vanish
  on redeploy; that was a real regression). Client: AppAuth `SuiteAuthManager`, client id
  `plate`, redirect `com.plate:/oauth2redirect`, "Sign in with Dragonfly" button with
  email/password fallback. Manifest overrides `RedirectUriReceiverActivity` with an AppCompat
  theme (`tools:node="merge"`) — required, the app theme is `android:Theme.Material` and AppAuth
  crashes on redirect without it.

## AI guardrails

Mirror Spotter's (its CLAUDE.md "AI Guardrails" section is the reference): server-side system
prompt, scope limited to nutrition coaching (no medical advice), structured output validated by
Pydantic before persisting, user chat treated as untrusted, DB-derived trusted context, LLM has
no tool/DB/file access, photo/AI numbers are always user-confirmed estimates. Keep prompt +
guardrail logic auditable in one module.

## Testing (read this before running pytest locally)

CI is the source of truth (`ci.yml`: ruff, server pytest, Android unit + Roborazzi screenshot
tests, assembleDebug). Locally the server suite has three landmines, all solved:

1. **Stale `server/.env` DB password** — the root `.env` rotated the live container's creds, so
   pytest against the default config dies with InvalidPasswordError. Don't "fix" the live
   container; use a throwaway DB (below).
2. **`localhost` vs `127.0.0.1`** — Docker publishes IPv4-only; `localhost` resolves ::1 first
   and each fresh connection eats a fallback stall (minutes of it). Always 127.0.0.1.
3. **Event-loop pooling** — if you see "Task attached to a different loop", set `DB_NULLPOOL`
   (SQLAlchemy's pool binds asyncpg connections to the creating loop).

Verified recipe (~21 s full suite):

```powershell
docker run -d --name plate-test-db -e POSTGRES_USER=plate -e POSTGRES_PASSWORD=plate `
  -e POSTGRES_DB=plate_test -p 127.0.0.1:5440:5432 postgres:16
$env:DATABASE_URL = "postgresql+asyncpg://plate:plate@127.0.0.1:5440/plate_test"
cd server; python -m pytest
```

Known env-dependent failure: `test_discover_disabled_without_key` fails whenever the local
`.env` has a real `SPOONACULAR_API_KEY`/`USDA_API_KEY` set — pre-existing, green in CI, ignore
locally.

## Deployment

Identical shape to Spotter: root compose (`db` + `server` + `cloudflared` behind the `tunnel`
profile — keep `COMPOSE_PROFILES=tunnel` in the root `.env` or a deploy drops the tunnel),
migrations on boot, `GET /version` stamped by `deploy/redeploy.ps1`, manual `workflow_dispatch`
with `ref` = rollback. Full operator guide: [deploy/README.md](deploy/README.md).
`LM_STUDIO_BASE_URL=http://host.docker.internal:1234/v1` inside the container (503 from `/ai/*`
or `/foods/photo` means LM Studio unreachable; 530 means tunnel down).

## Design system (PULSE)

Plate consumes the shared **Pulse library** (`design.pulse:pulse-ui`) via a Gradle composite build
(`settings.gradle.kts` → `includeBuild("../../Pulse")`; the sibling `Pulse` repo must sit next to
`Plate`, and CI/release check it out) — migrated off the in-tree copy 2026-07-03. Plate **leads
green** via `PulseAccent.Green`. The app-side semantic layer is `ui/theme/PlateTheme.kt`: the
nutrition channel map (`PulseColors` — protein/carbs/fat/calories), the signature **emerald hero
gradient** (green→forest, its own `PulseColors.heroGradient`, NOT the library's green→blue accent
gradient), and a small M3 `secondary`-family reconcile to keep the carbs-blue voice. Pull via
`PlateTheme.pulse`. Generic tokens + components (`PanelCard`, `PulseButton`, `DataText`, …) come
from the library — **do not reintroduce in-tree copies**; fix them in Pulse (rebuild Cookbook +
Dragonfly + Plate when you do). Keep AGP/Kotlin/Compose-BOM aligned with Pulse's catalog.

## Conventions & guardrails

- **Update `ARCHITECTURE.md` in the same PR** when a change alters architecture — a module's
  responsibility, a layer boundary, a cross-app contract, or the data model. Silently-drifting
  docs are how Spotter's API docs said `/plans` for a round (ROADMAP2 T2 #5c).
- Match Spotter's code style, package naming, workflows; if this doc conflicts with how Spotter
  actually does something, **Spotter wins** — flag the conflict.
- Prefer Alembic migrations over manual schema edits; Pydantic at every boundary.
- External nutrition data is cached, attributed (OFF/ODbL + USDA in Settings → About), and
  rate-limited — never live-hit OFF/USDA per keystroke, and never ship API keys in the APK.
- Personal-use app, not a medical product — keep that scope in coach prompts and UI copy.
