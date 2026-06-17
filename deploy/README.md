# Deploying Plate (server + Cloudflare Tunnel on boot)

systemd units that bring the Plate API and a Cloudflare Tunnel up together at
boot, so your public `https://plate.<yourdomain>` hostname is always live.

```
[phone / 5G] --HTTPS--> [Cloudflare edge] --tunnel--> [cloudflared] --HTTP--> [Plate API @127.0.0.1:8000] --> [Postgres]
```

The API binds to localhost only; `cloudflared` is the sole public entrypoint.
Plate mirrors [Spotter](https://github.com/CDRaab01/Spotter)'s deployment model
exactly — the two apps run side by side the same way.

## Files
| File | Purpose |
|------|---------|
| `plate.service` | Runs `server/run.sh` (migrations + uvicorn). After Postgres. |
| `cloudflared.service` | Runs the named tunnel. Ordered after `plate.service`. |
| `cloudflared.config.example.yml` | Template for `~/.cloudflared/config.yml`. |
| `install.sh` | Renders the unit templates, installs them, enables both. |
| `redeploy.ps1` / `redeploy.sh` | One redeploy logic per OS (used by hand + by the Deploy workflow). |

## One-time setup

1. **Configure the server.** In `server/`, `cp .env.example .env` and set at least
   `SECRET_KEY`, plus the public-deploy toggles: `REGISTRATION_INVITE_CODE`,
   `TRUST_PROXY=true`, `HSTS_ENABLED=true`, `DOCS_ENABLED=false`.

2. **Build the venv once** (boot-time install would be slow):
   ```bash
   cd server && ./run.sh        # Ctrl-C once it says "Application startup complete"
   ```

3. **Set up the Cloudflare Tunnel** (needs a domain on Cloudflare):
   ```bash
   cloudflared tunnel login
   cloudflared tunnel create plate
   cloudflared tunnel route dns plate plate.example.com
   cp deploy/cloudflared.config.example.yml ~/.cloudflared/config.yml
   # edit it: set the tunnel UUID/credentials-file and your hostname
   ```

4. **Install + enable the services:**
   ```bash
   sudo ./deploy/install.sh
   sudo systemctl start plate.service cloudflared.service
   ```

That's it — both are now `enabled`, so they start on every boot. The tunnel waits
for the API, and the API waits for Postgres.

## Operating
```bash
systemctl status plate.service cloudflared.service
journalctl -u plate.service -f            # API logs
journalctl -u cloudflared.service -f      # tunnel logs
sudo systemctl restart plate.service      # after pulling new code
```

## Notes
- `install.sh` runs the services as the user who invoked `sudo` (override with
  `RUN_USER=...`). Adjust `HOST`/`PORT` in `plate.service` if needed.
- Postgres must run as a systemd service named `postgresql.service` (the default on
  Debian/Ubuntu). On other distros, edit the `After=`/`Requires=` line.
- The app finds the database via `DATABASE_URL` in `server/.env`.

> The systemd path above runs the API straight from the venv. The Docker Compose
> path below (`docker compose up -d`) is the alternative — pick one; don't run both
> against the same port. The remote-redeploy pipeline drives the Docker path.

## Remote redeploy on push to main (self-hosted runner)

Refresh the running server **without touching the machine**: when `main` updates
and CI passes, a self-hosted GitHub Actions runner on the host pulls the new code,
rebuilds, and restarts the Docker stack. The runner long-polls GitHub *outbound*,
so there are **no inbound ports** to open on a home network.

```
[push to main] -> [CI green] -> [Deploy workflow] -> [self-hosted runner on host]
                                                          |
                                redeploy script: git reset --hard -> docker compose up -d --build -> /health
```

The redeploy logic lives in one place per OS — `deploy/redeploy.ps1` (Windows /
Docker Desktop, the primary target) and `deploy/redeploy.sh` (Linux/macOS) — so a
human and the workflow run the exact same steps: fetch, check out the ref, rebuild
+ restart, health-gate on `/health`, prune old images. Migrations apply
automatically on container boot (`server/docker-entrypoint.sh`).

### One-time setup

1. **Install the runner on the host.** In GitHub: **Settings → Actions → Runners →
   New self-hosted runner**, follow the download/configure steps, and give it the
   label **`plate`** (the `Deploy` workflow targets `runs-on: [self-hosted, plate]`).
   Install it as a service so it survives reboots (`svc install` / `svc start` on
   Windows; `./svc.sh install` on Linux). It can share a host with Spotter's runner —
   just give each runner its own label.

2. **Point the workflow at your deployment clone.** Set a repo **Actions variable**
   (Settings → Secrets and variables → Actions → Variables) named **`PLATE_DIR`**
   to the absolute path of the clone you run `docker compose` from, e.g.
   `C:\Plate`. The workflow never checks out code — it drives this canonical
   clone, which owns `server/.env` and the `pgdata` volume.

3. **Windows / Docker Desktop caveat (important).** `docker compose` only works for
   the runner if Docker Desktop is running for the runner's account:
   - Run the runner service under a user that is in the **`docker-users`** group.
   - Make sure **Docker Desktop is running** in that session — enable Docker
     Desktop's *"Start Docker Desktop when you log in"* and keep that user logged
     in, or run Docker Desktop as that same account. If the runner can't reach the
     Docker engine, the deploy step fails fast with a clear error.

### Cloudflare Tunnel users — keep the tunnel up across deploys

`cloudflared` lives behind the `tunnel` profile in `docker-compose.yml`, so a plain
`docker compose up -d --build` (what the redeploy runs) **excludes it** — a deploy
would tear the tunnel down and your public hostname would start returning Cloudflare
`530` errors. To keep it in the managed set on every deploy, add this to the **root
`.env`** (next to `docker-compose.yml`, where `TUNNEL_TOKEN` already lives):

```
COMPOSE_PROFILES=tunnel
```

Compose reads `COMPOSE_PROFILES` automatically, so `up` always includes `cloudflared`.
It's gitignored, so `git reset --hard` never touches it.

`cloudflared` has a healthcheck (`cloudflared tunnel ready` against its local metrics
endpoint), so `docker compose ps` shows whether the tunnel actually has live edge
connections — a missing/invalid `TUNNEL_TOKEN` shows up as `unhealthy` instead of a
silent green deploy. The redeploy scripts print this status after the health gate.

### Postgres credentials

The compose file defaults to `plate`/`plate` for the local-only Postgres (the port
is bound to `127.0.0.1`). To rotate, set `POSTGRES_USER` / `POSTGRES_PASSWORD` /
`POSTGRES_DB` in the **root `.env`** — the `db` service and the server's
`DATABASE_URL` read the same variables, so they can't drift apart. (Changing the user
or database name after first boot requires recreating the `pgdata` volume; changing
just the password also requires an `ALTER USER` inside the running container.)

### LM Studio from inside the Docker container

Inside the `server` container, `localhost` is the container — not your host — so the
default `LM_STUDIO_BASE_URL=http://localhost:1234/v1` can't reach LM Studio running on
the machine, and `/ai/chat` (and `/foods/photo`) return `503`. For the Docker
deployment, set this in `server/.env`:

```
LM_STUDIO_BASE_URL=http://host.docker.internal:1234/v1
```

(A `530` instead of `503` means the Cloudflare tunnel itself is down — see above.)

### Spotter-awareness from inside the Docker container

Plate's training-day bump reads Spotter's `GET /workouts`. Point `SPOTTER_BASE_URL`
(in `server/.env`) at how the container reaches Spotter: Spotter's public tunnel
hostname (`https://spotter.example.com`) works as-is; a Spotter API on the same host
is reachable at `http://host.docker.internal:8000`. `CROSS_APP_SECRET` must be the
**same value** in both apps' `.env`. Unset ⇒ the integration is off (never a training
day), which degrades gracefully.

### How it fires

- **Automatic:** every push to `main` runs CI; when CI succeeds, the `Deploy`
  workflow runs on the host and redeploys the CI'd commit. A red CI never deploys.
- **Manual / rollback:** **Actions → Deploy → Run workflow**. Leave `ref` as
  `origin/main` for a plain redeploy, or enter a **prior commit SHA** to roll back.
  (Code rolls back via `git reset`; Alembic migrations are forward-only, so a
  schema-changing rollback would need a down-migration.)

### Confirming the deploy landed

The redeploy scripts stamp the running build with the deployed commit. To verify:
- **In the app:** **Settings → About** shows the app version and the connected
  **Server** version + short commit (e.g. `0.1.0 · a1b2c3d`).
- **Direct:** `curl http://127.0.0.1:8000/version` →
  `{"name","version","commit","built_at"}`. `commit`/`built_at` are `unknown` for a
  plain manual `docker compose up` (only the redeploy scripts stamp them).

### Run it by hand

You can always redeploy directly on the host without GitHub (Windows PowerShell —
no PowerShell 7 needed, matching the Deploy workflow):

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\redeploy.ps1            # deploy origin/main
powershell -ExecutionPolicy Bypass -File .\deploy\redeploy.ps1 -Ref 1a2b3c4   # roll back
```
```bash
./deploy/redeploy.sh               # deploy origin/main  (Linux/macOS)
./deploy/redeploy.sh 1a2b3c4       # roll back
```
