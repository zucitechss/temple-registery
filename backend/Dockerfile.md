# temple-registry-backend
# ──────────────────────────────────────────────────────────────────────────────
# Build:   docker build -t temple-registry-backend .
# Run dev: docker run -p 8080:8080 --env-file .env temple-registry-backend
# ──────────────────────────────────────────────────────────────────────────────

# Required environment variables (must be provided at runtime — see the
# Dockerfile's own header comment and backend/.env.example for the full,
# authoritative list):
# DB_URL                    jdbc:mysql://<host>:<port>/<db>?...
# DB_USERNAME, DB_PASSWORD
# APP_ENCRYPTION_KEY        32 bytes (AES)
# APP_HMAC_KEY              32 bytes
# APP_JWT_PRIVATE_KEY       PEM contents, RS256 (C-1) — this image ships no
# APP_JWT_PUBLIC_KEY        key file; the build fails if one ever reaches the jar (H-8)
# APP_BASE_URL
# APP_CORS_ALLOWED_ORIGINS  exact SPA origin, e.g. https://temple-registery.vercel.app (H-1)
# SMTP_HOST, EMAIL_USERNAME, EMAIL_PASSWORD
#
# There is no S3/AWS integration in this codebase — uploads and exports are
# local-filesystem only (see PERSISTENT STORAGE below, H-3).

# For local dev, override via docker-compose or application-dev.yml.

# ──────────────────────────────────────────────────────────────────────────────
# DEPLOYMENT TOPOLOGY — RUN EXACTLY ONE REPLICA (H-7)
# ──────────────────────────────────────────────────────────────────────────────
# Scale this service to 1 instance. It is NOT safe to run two or more.
#
# The application holds state in process that is not shared between instances,
# so additional replicas do not just add throughput — they duplicate work that
# users can see:
#   * duplicate emails and in-app notifications (outbox pollers run every 5-10s
#     and claim rows with no locking)
#   * duplicate deadline warnings
#   * missed real-time SSE updates (emitters are instance-local; the underlying
#     notification is still persisted, so this is degraded, not lost)
#   * stale authorization for up to 5 minutes (in-process policy cache)
#   * missing documents and 404s on completed exports (filesystem-local storage)
#
# Multi-replica support is future work. See HLD section 9.2 for the inventory
# and what each item needs before scaling out.

# ──────────────────────────────────────────────────────────────────────────────
# PERSISTENT STORAGE — REQUIRED EVEN WITH ONE REPLICA
# ──────────────────────────────────────────────────────────────────────────────
# APP_STORAGE_BASE_DIR   uploaded documents   (default ./uploads  -> /app/uploads)
# TRM_EXPORT_BASE_DIR    generated exports    (default ./exports  -> /app/exports)
#
# WORKDIR is /app and this image declares no VOLUME, so with the defaults both
# directories live in the container's writable layer and are DESTROYED on every
# redeploy. Mount durable volumes, e.g.:
#
#   docker run -p 8080:8080 --env-file .env \
#     -v temple-uploads:/data/uploads \
#     -v temple-exports:/data/exports \
#     -e APP_STORAGE_BASE_DIR=/data/uploads \
#     -e TRM_EXPORT_BASE_DIR=/data/exports \
#     temple-registry-backend
