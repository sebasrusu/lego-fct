#!/usr/bin/env bash
# Small utility to list / delete blobs in the AZURE container configured in .env
# Usage:
#   ./scripts/cleanbob.sh          -> interactive prompt to delete ALL blobs in container 'media'
#   ./scripts/cleanbob.sh --dry    -> list blobs (no delete)
#   ./scripts/cleanbob.sh --prefix <p> [--force] -> delete blobs with given prefix
# Requires: az cli logged in and .env with BlobStoreConnection set

set -euo pipefail

ROOT="$(cd "$(dirname "${0}")/.." && pwd)"
ENVFILE="$ROOT/.env"

if [ ! -f "$ENVFILE" ]; then
  echo ".env not found at $ENVFILE" >&2
  exit 2
fi

CONN=$(grep -m1 '^BlobStoreConnection=' "$ENVFILE" | cut -d'=' -f2- || true)
if [ -z "$CONN" ]; then
  echo "BlobStoreConnection not found in $ENVFILE" >&2
  exit 3
fi

CONTAINER="media"
DRY_RUN=0
FORCE=0
PREFIX=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry|-n) DRY_RUN=1; shift;;
    --force|-f) FORCE=1; shift;;
    --prefix) PREFIX="$2"; shift 2;;
    -c|--container) CONTAINER="$2"; shift 2;;
    -h|--help) echo "Usage: $0 [--dry] [--force] [--prefix <prefix>] [--container <name>]"; exit 0;;
    *) echo "Unknown arg: $1" >&2; exit 4;;
  esac
done

echo "Container: $CONTAINER"
[ -n "$PREFIX" ] && echo "Prefix: $PREFIX"

echo "Listing blobs..."
if [ -n "$PREFIX" ]; then
  az storage blob list --connection-string "$CONN" --container-name "$CONTAINER" --prefix "$PREFIX" --query "[].{name:name, size:properties.contentLength}" -o table
else
  az storage blob list --connection-string "$CONN" --container-name "$CONTAINER" --query "[].{name:name, size:properties.contentLength}" -o table
fi

if [ "$DRY_RUN" -eq 1 ]; then
  echo "Dry run; no blobs deleted."
  exit 0
fi

if [ "$FORCE" -ne 1 ]; then
  read -r -p "Delete the above blobs from container '$CONTAINER'? (yes/NO) " ans
  if [ "$ans" != "yes" ]; then
    echo "Aborted."
    exit 0
  fi
fi

if [ -n "$PREFIX" ]; then
  echo "Deleting blobs with prefix '$PREFIX'..."
  az storage blob delete-batch --connection-string "$CONN" --source "$CONTAINER" --pattern "${PREFIX}*"
else
  echo "Deleting ALL blobs in container '$CONTAINER'..."
  az storage blob delete-batch --connection-string "$CONN" --source "$CONTAINER"
fi

echo "Done."