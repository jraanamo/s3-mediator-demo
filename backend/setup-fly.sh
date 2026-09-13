#!/usr/bin/env bash
# Pushes backend/.env to Fly secrets. Run from the backend/ directory after
# filling in .env (copy .env.example if you haven't already).
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  echo "backend/.env not found — copy .env.example to .env and fill in real values first" >&2
  exit 1
fi

set -a
source .env
set +a

fly secrets set \
  JWT_SECRET="$JWT_SECRET" \
  JWT_EXPIRY_SECONDS="$JWT_EXPIRY_SECONDS" \
  S3_ENDPOINT="$S3_ENDPOINT" \
  S3_STS_ENDPOINT="$S3_STS_ENDPOINT" \
  S3_REGION="$S3_REGION" \
  S3_BUCKET="$S3_BUCKET" \
  S3_ACCESS_KEY_ID="$S3_ACCESS_KEY_ID" \
  S3_SECRET_ACCESS_KEY="$S3_SECRET_ACCESS_KEY" \
  S3_UPLOAD_ROLE_ARN="$S3_UPLOAD_ROLE_ARN" \
  PUBLISH_INTERVAL_SECONDS="$PUBLISH_INTERVAL_SECONDS" \
  INBOX_POLL_INTERVAL_SECONDS="$INBOX_POLL_INTERVAL_SECONDS" \
  INBOX_PAGE_SIZE="$INBOX_PAGE_SIZE"
