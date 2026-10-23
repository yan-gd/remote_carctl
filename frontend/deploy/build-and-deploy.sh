#!/usr/bin/env bash
# Build the React console on the server and publish it to the nginx root.
# Run from anywhere: bash /opt/remote_carctl/frontend/deploy/build-and-deploy.sh
set -euo pipefail

SOURCE_DIR="/opt/remote_carctl/frontend"
TARGET_DIR="/var/www/agilex"

if ! command -v npm >/dev/null 2>&1; then
  echo "npm not found: install Node.js 20.19+ / 22.12+ first" >&2
  exit 1
fi

cd "$SOURCE_DIR"
node --version
npm --version

# npm ci is reproducible and requires package-lock.json (committed).
npm ci --no-audit --no-fund
npm run typecheck
npm test
npm run build

if [ ! -f dist/index.html ]; then
  echo "dist/index.html missing: build failed" >&2
  exit 1
fi

sudo install -d -m 755 "$TARGET_DIR"
sudo rsync -a --delete dist/ "$TARGET_DIR/"
echo "deployed dist/ -> $TARGET_DIR"
