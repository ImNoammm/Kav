#!/bin/sh
# Installs your own Kav server: puts its latest release's docker-compose.yml in a folder of its own, starts its setup
# page, and shows where to open it. Everything else is set on that page. Run on the machine it's for:
#   curl -fsSL https://raw.githubusercontent.com/ImNoammm/kav/main/server/selfhost/install.sh | sh
# KAV_DIR: where it goes (~/kav-server). KAV_VERSION: a release's tag instead of the latest. KAV_REPO and KAV_IMAGES: a
# fork of yours on GitHub (you/kav) and the registry its images are in (ghcr.io/you).
set -eu
DIR=${KAV_DIR:-$HOME/kav-server}
REPO=$(echo "${KAV_REPO:-ImNoammm/kav}" | sed 's|^https://github.com/||; s|/*$||')

command -v docker >/dev/null || { echo "Docker is needed first: https://docs.docker.com/engine/install/"; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "Docker Compose is needed first (the docker compose plugin)."; exit 1; }

if [ -e "$DIR/docker-compose.yml" ]; then
  echo "Already installed in $DIR: updates come from its setup page."
else
  VERSION=${KAV_VERSION:-}
  if [ -z "$VERSION" ]; then
    # The latest release (not a pre-release), as GitHub has it.
    VERSION=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -n 1)
    [ -n "$VERSION" ] || { echo "Couldn't find the latest release of $REPO."; exit 1; }
  fi
  echo "Downloading Kav $VERSION…"
  mkdir -p "$DIR/stages"
  # The release's own files: the services, what each .env line means, and the HTTPS proxy's settings.
  for f in docker-compose.yml .env.example Caddyfile; do
    curl -fsSL "https://raw.githubusercontent.com/$REPO/$VERSION/server/selfhost/$f" -o "$DIR/$f.part" ||
      { echo "Couldn't download $f of Kav $VERSION."; rm -f "$DIR/$f.part"; exit 1; }
  done
  for f in docker-compose.yml .env.example Caddyfile; do mv "$DIR/$f.part" "$DIR/$f"; done
  {
    echo "KAV_VERSION=$VERSION"
    # The server's own key: its services sign their requests to kav-live with it (and phones, with ACCESS=key).
    echo "KAV_APP_KEY=$(head -c 18 /dev/urandom | base64 | tr '+/' '-_')"
    [ -z "${KAV_REPO:-}" ] || echo "KAV_REPO=$REPO"
    [ -z "${KAV_IMAGES:-}" ] || echo "KAV_IMAGES=$KAV_IMAGES"
  } > "$DIR/.env"
  chmod 600 "$DIR/.env"
fi

cd "$DIR"
docker compose up -d setup
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do [ -s data/setup-code ] && break; sleep 1; done

ip=$(hostname -I 2>/dev/null | awk '{print $1}')
echo
if [ -s data/setup-code ]; then
  echo "Kav's setup page: http://${ip:-localhost}:${SETUP_PORT:-8090}/?code=$(cat data/setup-code)"
  echo "Open it in a browser on this machine's network to choose what this server runs and start it."
  echo "Reaching this machine over the internet? Tunnel to it first, then use localhost in that address:"
  echo "  ssh -L ${SETUP_PORT:-8090}:localhost:${SETUP_PORT:-8090} <this machine>"
else
  echo "Kav's setup page: http://${ip:-localhost}:${SETUP_PORT:-8090}/ (it has an account already: sign in)"
fi
