# temple-registry-backend
# ──────────────────────────────────────────────────────────────────────────────
# Build:   docker build -t temple-registry-backend .
# Run dev: docker run -p 8080:8080 --env-file .env temple-registry-backend
# ──────────────────────────────────────────────────────────────────────────────

# Required environment variables (must be provided at runtime):
# DB_URL              jdbc:mysql://mysql:3306/temple_registry?...
# DB_USERNAME         <db user>
# DB_PASSWORD         <db password>
# JWT_ACCESS_SECRET   (not used — key material is mounted from /app/keys/)
# ENCRYPTION_KEY      32-char AES key (hex or plain — must be exactly 32 bytes)
# AWS_REGION          ap-south-1
# S3_BUCKET_NAME      temple-registry-docs-<env>
# CORS_ALLOWED_ORIGINS https://portal.temple-registry.gov.in

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
