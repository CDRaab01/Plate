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
    services/        # auth_service (register/login/forgot/reset)
    routers/         # auth, users
  alembic/           # migrations (0001 = initial tables)
  tests/             # auth + repository-layer tests (Postgres)
```

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

## Data model (Phase 1)

- `users` — account + reset-token fields (mirrors Spotter).
- `foods` — canonical, source-tagged (`usda` | `off` | `user`) nutrition; stored **per 100g and
  per serving**; secondary nutrients (fiber, sugar, sat fat, cholesterol, sodium) included.
- `food_log_entries` — meal-bucketed log rows carrying a **denormalized macro snapshot** so edits
  to the source food never rewrite history (`food_id` is `SET NULL` on food delete).
- `user_goals` — goal type + body inputs for the Phase 3 targets engine.
- `daily_targets` — computed kcal/macros, unique per `(user, date)`.

No secrets in the repo: `DATABASE_URL`, `SECRET_KEY`, etc. come from the environment.
