#!/usr/bin/env bash
#
# Install + enable the Plate API and Cloudflare Tunnel as systemd services so
# both come up at boot (the tunnel ordered after the API).
#
# Run as root from anywhere:   sudo ./deploy/install.sh
#
# It renders the unit templates in this directory with your repo path, user, and
# cloudflared paths, installs them to /etc/systemd/system, and enables them.
# cloudflared.service is only installed if the cloudflared binary is found.
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "error: run as root (sudo ./deploy/install.sh)" >&2
  exit 1
fi

DEPLOY_DIR="$(cd "$(dirname "$0")" && pwd)"
PLATE_DIR="$(cd "$DEPLOY_DIR/.." && pwd)"
# The account the services run as — the user who invoked sudo, not root.
RUN_USER="${RUN_USER:-${SUDO_USER:-root}}"
RUN_HOME="$(getent passwd "$RUN_USER" | cut -d: -f6)"
CLOUDFLARED_CONFIG="${CLOUDFLARED_CONFIG:-$RUN_HOME/.cloudflared/config.yml}"
UNIT_DIR=/etc/systemd/system

echo "Repo:        $PLATE_DIR"
echo "Run as user: $RUN_USER"

if [[ ! -x "$PLATE_DIR/server/run.sh" ]]; then
  echo "error: $PLATE_DIR/server/run.sh not found or not executable" >&2
  exit 1
fi
if [[ ! -f "$PLATE_DIR/server/.env" ]]; then
  echo "warning: $PLATE_DIR/server/.env is missing — create it before starting (cp .env.example .env)." >&2
fi

render() {  # render <template> <dest>  (substitutes the __PLACEHOLDER__ tokens)
  sed -e "s|__RUN_USER__|$RUN_USER|g" \
      -e "s|__PLATE_DIR__|$PLATE_DIR|g" \
      -e "s|__CLOUDFLARED_BIN__|${CLOUDFLARED_BIN:-}|g" \
      -e "s|__CLOUDFLARED_CONFIG__|$CLOUDFLARED_CONFIG|g" \
      "$1" > "$2"
}

echo "Installing plate.service…"
render "$DEPLOY_DIR/plate.service" "$UNIT_DIR/plate.service"

CLOUDFLARED_BIN="$(command -v cloudflared || true)"
if [[ -n "$CLOUDFLARED_BIN" ]]; then
  echo "Installing cloudflared.service (cloudflared at $CLOUDFLARED_BIN)…"
  render "$DEPLOY_DIR/cloudflared.service" "$UNIT_DIR/cloudflared.service"
else
  echo "cloudflared not found on PATH — skipping cloudflared.service."
  echo "  Install it, set up the tunnel, then re-run this script."
fi

systemctl daemon-reload
systemctl enable plate.service
[[ -n "$CLOUDFLARED_BIN" ]] && systemctl enable cloudflared.service

cat <<EOF

Installed and enabled (will start on boot). To start now:
  sudo systemctl start plate.service${CLOUDFLARED_BIN:+ cloudflared.service}

First-run notes:
  * Run '$PLATE_DIR/server/run.sh' once manually first so the venv is built
    (boot-time pip install is slow and needs network).
  * cloudflared needs ~/.cloudflared/config.yml — see deploy/cloudflared.config.example.yml.

Logs:   journalctl -u plate.service -f   /   journalctl -u cloudflared.service -f
EOF
