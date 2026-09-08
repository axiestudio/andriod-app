#!/usr/bin/env bash
# fetch-context7-docs.sh — pull Android API docs via Context7 (key stays in shell).
# Usage: ./scripts/fetch-context7-docs.sh "<libraryId>" "<query>" [tokens]
# Example: ./scripts/fetch-context7-docs.sh "/websites/developer_android_media" \
#            "MediaProjection createVirtualDisplay screen capture streaming"
set -euo pipefail

LIBRARY_ID="${1:?usage: fetch-context7-docs.sh <libraryId> <query> [tokens]}"
QUERY="${2:?usage: fetch-context7-docs.sh <libraryId> <query> [tokens]}"
TOKENS="${3:-8000}"

MOBILE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -f "$MOBILE_DIR/../.env" ]; then
  # shellcheck disable=SC1091
  source "$MOBILE_DIR/../.env"
fi
if [ -z "${CONTEXT7_API_KEY:-}" ]; then
  echo "CONTEXT7_API_KEY not set (expected in repo-root .env)" >&2
  exit 1
fi

# Resolve a library id first if you only have keywords:
#   curl -s -H "Authorization: Bearer $CONTEXT7_API_KEY" \
#     "https://context7.com/api/v1/search?query=android+media+projection"
curl -s -H "Authorization: Bearer $CONTEXT7_API_KEY" \
  --get --data-urlencode "libraryId=$LIBRARY_ID" \
  --data-urlencode "query=$QUERY" \
  --data-urlencode "tokens=$TOKENS" \
  "https://context7.com/api/v2/context"
echo
