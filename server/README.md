# Plate — backend (FastAPI)

Sister service to [Spotter](https://github.com/CDRaab01/Spotter); mirrors its stack and
conventions (FastAPI + async SQLAlchemy + Alembic + Postgres, JWT auth, `pip`/`hatchling`,
`ruff`, `pytest`).

## Spotter integration (§8) — decision of record

The Plate spec's plan of record is a **shared backend + shared `users` table** with Spotter, but
that is **gated and not yet confirmed** (CLAUDE.md §8 — confirm before building the integration in
Phase 7). Until then, Phase 1 ships Plate's **own `users` table**, mirroring Spotter's auth
mechanism exactly (bcrypt password hashing, HS256 access/refresh JWTs, identical register/login/
refresh/forgot/reset flow). This keeps Plate self-contained and testable now and is reversible: if
the shared-backend path is confirmed, Plate points at the shared table instead.

## Layout

```
server/
  app/
    config.py        # pydantic-settings; env-driven
    database.py      # async engine + session
    security.py      # password hashing + JWT + get_current_user
    limiter.py       # slowapi rate-limit key
    main.py          # app factory, security headers, /health, /version
    models/          # User, Food, FoodLogEntry, UserGoal, DailyTarget
    schemas/         # pydantic request/response models
    services/        # auth_service, food_service (search/cache), log_service (CRUD/totals)
                     #   services/ai/ — coach client + context + prompts, vision (photo logging)
    foods/           # FoodSource abstraction + USDA/OFF sources + normalization/dedup
    nutrition/       # pure macro math: portion scaling, daily totals (targets engine in Phase 3)
    routers/         # auth, users, foods, log
  alembic/           # migrations (0001 = initial tables)
  tests/             # auth, repository, nutrition, foods, log tests (Postgres)
```

## Food search & logging (Phase 2)

- `GET /foods/search?q=` — **local-cache-first** search: the `foods` table is consulted first and
  only a cache miss reaches USDA → OFF, whose results are normalized, deduplicated (by barcode and
  normalized name), cached, and served locally thereafter (CLAUDE.md §5).
- `POST /foods` — create a user-defined custom food (`source='user'`).
- `GET /foods/{id}` — fetch one food.
- `POST /log` / `PUT /log/{id}` / `DELETE /log/{id}` — manage `food_log_entries`, each carrying a
  **denormalized macro snapshot** taken at log time so edits to the source food never rewrite
  history.
- `GET /log?date=` — a day's entries split into Breakfast/Lunch/Dinner/Snacks with per-meal and
  day totals plus computed daily targets (or the 2000 kcal placeholder for users with no goal).

External provider keys are **server-side only** (`USDA_API_KEY`) and never shipped in the APK; OFF
sends a descriptive `User-Agent` and requests only the nutriments we store. No live USDA/OFF calls
are made in tests — `FOOD_SEARCH_LIVE` is off for the suite and providers are mocked.

## Barcode scanning (Phase 4)

- `GET /foods/barcode/{code}` — resolve a scanned barcode **local-cache-first**: the `foods` table
  is consulted by barcode and only a miss reaches **Open Food Facts** (the barcode authority —
  CLAUDE.md §5/§6), whose product is normalized, cached, and served locally thereafter. Returns
  `404` when no product is found. USDA is not consulted on the barcode path.

The Android client scans on-device with ML Kit (CameraX preview) and calls this endpoint; the
OFF/ODbL and USDA attributions required by CLAUDE.md §5 are shown on the app's About screen. As
elsewhere, no live OFF calls happen in tests — the scan path is covered with a fake barcode source
and `FOOD_SEARCH_LIVE` off.

## Running locally

```bash
cd server
pip install -e ".[dev]"
export DATABASE_URL="postgresql+asyncpg://plate:plate@localhost:5432/plate_test"
export SECRET_KEY="dev-secret-change-me"
alembic upgrade head
uvicorn app.main:app --reload
```

## Tests

```bash
ruff check app
alembic upgrade head
pytest tests/ -v
```

CI runs all three against a `postgres:16` service. No live external APIs are called in tests.

## Targets engine (Phase 3)

All macro-target math lives in `app/nutrition/` and is pure + exhaustively unit-tested; clients only
ever display these numbers, never recompute them.

- `PUT /goals` — set the user's goal (body inputs + goal type + target weight-change rate). Each
  call **appends a new row**; the most recent is the active goal so history is preserved.
- `GET /goals` — read the active goal (404 if none set yet).
- `GET /goals/targets?date=` — the computed kcal/macro targets for a date (404 until a goal is
  set). Computed via Mifflin-St Jeor BMR → activity-factor TDEE → goal-rate adjustment (clamped to
  a 1200 kcal/day floor) → protein-first / fat-floor / carbs-remainder split. All constants in
  `app/nutrition/constants.py`, no inline magic numbers.
- `GET /log?date=` targets are **live from the goal** when one is set; the static 2000 kcal
  placeholder is used only for goal-less users to preserve backward compatibility.

`daily_targets` table exists but is written only from Phase 7 onward, when the training-day bump
makes per-date snapshots worth persisting.

## AI coach (Phase 5)

Chat with a nutrition coach backed by **Gemma 3 via LM Studio** — same wiring as Spotter's chat
(the OpenAI-compatible `/chat/completions` endpoint). Local-only inference; no hosted fallback.

- `POST /ai/chat` — send the conversation so far (`{messages: [{role, content}]}`), get the coach's
  reply (`{reply}`). Rate-limited to 20/minute since each call hits the model.
- The coach reasons over the user's **remaining macros + goal for today**, derived **server-side**
  from the active goal + the day's log (`app/services/ai/context_service.py`) — never trusted from
  the client. Spotter's training-day bump layers into this context in Phase 7.
- Guardrails (`app/services/ai/prompts.py`): a fixed system prompt scoped to food/recipes/macros, a
  request guard that rejects prompt-injection and out-of-scope/medical content (checked on **every**
  user turn, not just the latest), and a response scrub. Coach numbers are estimates the user
  confirms — Phase 5 surfaces them as plain chat and never auto-logs.

Config (server-side only, never in the APK): `LM_STUDIO_BASE_URL` (default
`http://localhost:1234/v1`), `LM_STUDIO_MODEL` (default `google/gemma-3-12b`), `LM_STUDIO_TIMEOUT`.
CI never reaches a real server — the HTTP client is injected with a mocked transport in tests
(`tests/test_ai.py`).

## Photo logging (Phase 6)

Estimate the foods + macros in a **meal photo** using the **vision-capable Gemma 3 via LM Studio**
(CLAUDE.md §3, §6). No bespoke CV model is trained — the multimodal model does the read, and the
result is always an **estimate the user confirms**; nothing is ever auto-committed.

- `POST /foods/photo` — multipart upload (`image`); returns an editable draft
  `{items: [{name, est_grams, kcal, protein_g, carbs_g, fat_g, confidence}], low_confidence, note}`.
  The endpoint **never writes to the database** — the client shows the draft for the user to
  confirm/edit, then logs each item via the normal `POST /foods` + `POST /log` flow. Rate-limited to
  10/minute since each call hits the model; rejects non-image uploads (`415`), empty (`400`), and
  oversize (`413`, `PHOTO_MAX_BYTES`) bodies before any model call.
- Robust parsing (`app/services/ai/photo_prompts.py`) tolerates the messiness of local-model output
  — code fences, preamble prose, a single object instead of a list, a list nested under a key,
  missing/mistyped fields, out-of-range confidence — and degrades unusable output to an empty,
  low-confidence draft (with a "retake or search manually" note) rather than erroring. The vision
  client (`app/services/ai/vision.py`) maps transport/HTTP failures the same way the coach does
  (502/503/504).

Config (server-side only): `LM_STUDIO_VISION_MODEL` (defaults to the same `google/gemma-3-12b`),
`PHOTO_MAX_BYTES`, `PHOTO_LOW_CONFIDENCE_THRESHOLD`. CI never reaches a real server — the HTTP client
is injected with a mocked transport, and parsing is covered exhaustively including malformed output
(`tests/test_photo.py`).

## Data model (Phase 1)

- `users` — account + reset-token fields (mirrors Spotter).
- `foods` — canonical, source-tagged (`usda` | `off` | `user`) nutrition; stored **per 100g and
  per serving**; secondary nutrients (fiber, sugar, sat fat, cholesterol, sodium) included.
- `food_log_entries` — meal-bucketed log rows carrying a **denormalized macro snapshot** so edits
  to the source food never rewrite history (`food_id` is `SET NULL` on food delete).
- `user_goals` — goal type + body inputs for the targets engine.
- `daily_targets` — computed kcal/macros, unique per `(user, date)`; populated in Phase 7.

No secrets in the repo: `DATABASE_URL`, `SECRET_KEY`, etc. come from the environment.
