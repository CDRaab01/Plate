#!/usr/bin/env bash
#
# Redeploy the Plate server from the canonical deployment clone (Linux/macOS).
#
# Bash parity for deploy/redeploy.ps1 — same five steps: fetch, reset to the
# requested ref, rebuild+restart via docker compose, health-gate on /health, then
# prune old images. Keeping one logic per OS means by-hand and automated deploys
# behave identically. Run it by hand or wire it into a self-hosted Linux runner.
#
# The script resolves its own location to find the repo root, so it operates on the
# real deployment clone (which owns server/.env and the pgdata volume). .env is
# gitignored and pgdata is a Docker named volume, so neither is touched by
# `git reset --hard`. Migrations run on container boot (docker-entrypoint.sh).
#
# Usage:
#   ./deploy/redeploy.sh                 # deploy origin/main
#   ./deploy/redeploy.sh 1a2b3c4         # roll back to a prior commit
#
# Env overrides:
#   HEALTH_URL        health endpoint (default http://127.0.0.1:8000/health)
#   TIMEOUT_SECONDS   health-check timeout (default 120)
set -euo pipefail

REF="${1:-origin/main}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8000/health}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-120}"

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "=== Plate redeploy ==="
echo "Repo:   $REPO_DIR"
echo "Ref:    $REF"

# 1 + 2. Fetch and check out the exact ref (clean, reproducible deploy).
git -C "$REPO_DIR" fetch --prune origin
git -C "$REPO_DIR" reset --hard "$REF"
echo "Deployed commit: $(git -C "$REPO_DIR" rev-parse --short HEAD)"

# 3. Rebuild + restart. Migrations run on container boot via the entrypoint.
#    Stamp the build so GET /version reports what's actually running.
export GIT_SHA="$(git -C "$REPO_DIR" rev-parse --short HEAD)"
export BUILT_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
docker compose --project-directory "$REPO_DIR" up -d --build --remove-orphans

# 4. Health gate — fail the run if the API doesn't come back healthy.
echo "Waiting for $HEALTH_URL (timeout ${TIMEOUT_SECONDS}s)..."
deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
healthy=0
while [[ $(date +%s) -lt $deadline ]]; do
  if curl -fsS --max-time 5 "$HEALTH_URL" 2>/dev/null | grep -q '"status"[[:space:]]*:[[:space:]]*"ok"'; then
    healthy=1
    break
  fi
  sleep 3
done
if [[ $healthy -ne 1 ]]; then
  echo "error: health check failed — $HEALTH_URL did not report ok within ${TIMEOUT_SECONDS}s." >&2
  exit 1
fi
echo "Health check passed."

# 4b. Report tunnel readiness when the tunnel profile is active (informational —
#     /health only proves the API is up locally, not that Cloudflare can reach it).
cf_id="$(docker compose --project-directory "$REPO_DIR" ps -q cloudflared 2>/dev/null || true)"
if [[ -n "$cf_id" ]]; then
  cf_health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}no healthcheck{{end}}' "$cf_id" 2>/dev/null || echo unknown)"
  echo "Tunnel (cloudflared) health: $cf_health"
  if [[ "$cf_health" == "unhealthy" ]]; then
    echo "warning: cloudflared reports unhealthy — the public hostname may be returning 530s." >&2
  fi
fi

# 5. Reclaim disk from superseded image layers.
docker image prune -f

echo "=== Redeploy complete ($(git -C "$REPO_DIR" rev-parse HEAD)) ==="
