# Plate

A calorie & macro tracking app with an on-device AI coach — sister application to
[Spotter](https://github.com/CDRaab01/Spotter), built on the same stack and conventions
(Android/Kotlin/Compose client + FastAPI/Postgres server + LM Studio for local LLM inference).

Track calories, protein, carbs and fat across Breakfast/Lunch/Dinner/Snacks; search USDA &
Open Food Facts foods; scan barcodes; estimate a meal from a photo; chat with a macro-aware
coach; save **recipes**, **quick-add** raw macros, and review a **weekly summary**. Daily targets
adjust on days you trained (Spotter-awareness).

See [CLAUDE.md](./CLAUDE.md) for the full architecture and build phases, and
[server/README.md](./server/README.md) for backend details (incl. the Spotter integration).

## Run the server on your computer (Docker)

Self-hosted via **Docker Compose**, exactly like
[Spotter](https://github.com/CDRaab01/Spotter) — the two apps run side by side the
same way. The root `docker-compose.yml` brings up `db` (Postgres), `server`
(FastAPI), and an optional `cloudflared` tunnel (behind the `tunnel` profile).
Migrations run automatically on container boot (`server/docker-entrypoint.sh` →
`alembic upgrade head`).

```bash
cd server && cp .env.example .env      # set SECRET_KEY (at minimum)
cd .. && docker compose up -d --build  # db + server on http://127.0.0.1:8001
curl http://127.0.0.1:8001/health      # {"status":"ok"}
```

> Plate publishes on host ports **8001** (API) and **5433** (Postgres) — not 8000/5432 —
> so it runs side-by-side with Spotter on the same machine. The container still listens
> on 8000 internally, so the Cloudflare tunnel is unaffected.

- **LM Studio (AI coach + photo):** inside the container `localhost` is the
  container, so set `LM_STUDIO_BASE_URL=http://host.docker.internal:1234/v1` in
  `server/.env` (else `/ai/chat` and `/foods/photo` return `503`).
- **Public hostname (optional):** put `COMPOSE_PROFILES=tunnel` and `TUNNEL_TOKEN=…`
  in a **root `.env`** to expose Plate over a Cloudflare Tunnel.
- **Remote redeploy + boot-time systemd units + rollback** — full operator guide in
  [deploy/README.md](./deploy/README.md). `GET /version` reports the running commit.

## Quick start (local dev, no Docker)

### 1. Postgres

```bash
docker run -d --name plate-db -p 5432:5432 \
  -e POSTGRES_USER=plate -e POSTGRES_PASSWORD=plate -e POSTGRES_DB=plate \
  postgres:16
```

### 2. Server (FastAPI)

```bash
cd server
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"

export DATABASE_URL="postgresql+asyncpg://plate:plate@localhost:5432/plate"
export SECRET_KEY="change-me"      # JWT signing secret (required)

alembic upgrade head               # apply migrations
uvicorn app.main:app --reload      # http://localhost:8000  (docs at /docs)
```

Optional environment (all server-side, never shipped in the APK):

| Env var | Purpose |
| --- | --- |
| `USDA_API_KEY` | Enables live USDA FoodData Central search (falls back to local cache + Open Food Facts when unset). |
| `LM_STUDIO_BASE_URL` | OpenAI-compatible endpoint for the Gemma 3 coach / photo vision (e.g. `http://localhost:1234/v1`). |
| `SPOTTER_BASE_URL` + `CROSS_APP_SECRET` | Enable Spotter-awareness — Plate reads Spotter's read-only `GET /workouts` to bump training-day targets. See [server/README.md](./server/README.md). |

### 3. Android client

Open `android/` in Android Studio, sync Gradle, and run on an emulator or device. The emulator
reaches the server at the build-time default `http://10.0.2.2:8000/`; override it by setting
`server.url` in `android/local.properties`.

## API surface

Full reference in [server/README.md](./server/README.md). Authenticated unless noted.

| Area | Endpoints |
| --- | --- |
| Auth | `POST /auth/register\|login\|refresh\|forgot-password\|reset-password`, `GET /users/me` |
| Foods | `GET /foods/search`, `GET /foods/barcode/{code}`, `POST /foods`, `POST /foods/photo` |
| Log | `POST /log`, `GET /log?date=`, `PUT/DELETE /log/{id}`, **`POST /log/quick-add`**, **`GET /log/summary?start=&end=`** |
| Recipes | **`GET/POST /recipes`**, **`GET/PATCH/DELETE /recipes/{id}`**, **`PUT /recipes/{id}/items`**, **`POST /recipes/{id}/log`** |
| Goals | `PUT/GET /goals`, `GET /goals/targets?date=` |
| Coach | `POST /ai/chat` |
| Ops | `GET /health`, `GET /version` (both unauthenticated) |

(**bold** = added in Phase 8.)

## Testing

```bash
cd server && pytest                              # backend (Postgres + mocked external sources)
cd android && ./gradlew :app:testDebugUnitTest   # ViewModel + Roborazzi screenshot tests
cd android && ./gradlew :app:assembleDebug       # debug APK
```

CI (`.github/workflows/ci.yml`) runs `ruff check app`, server `pytest`, Android unit tests,
screenshot comparison, and `assembleDebug` on every push/PR.

## Attribution

Plate uses third-party nutrition data, surfaced in the app under **Settings → About**:

- **Open Food Facts** — packaged-food and barcode data, used under the
  **Open Database License (ODbL)**. *Data from Open Food Facts, ODbL.*
- **USDA FoodData Central** — whole-food nutrition data from the U.S. Department of Agriculture.

No API keys or secrets are committed to the repo; the USDA key, LM Studio URL, database
credentials and cross-app secret are supplied via environment variables only.
