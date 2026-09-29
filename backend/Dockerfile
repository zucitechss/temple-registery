# temple-registry-backend — production image
#
# Build:  docker build -t temple-registry-backend .
#
# Required at runtime — every one of these has no default, so a missing value
# aborts startup rather than silently falling back to a development value:
#   DB_URL  DB_USERNAME  DB_PASSWORD
#   APP_ENCRYPTION_KEY  APP_HMAC_KEY              32 bytes each
#   APP_JWT_PRIVATE_KEY  APP_JWT_PUBLIC_KEY       PEM contents (C-1)
#   APP_BASE_URL  APP_CORS_ALLOWED_ORIGINS
#   SMTP_HOST  EMAIL_USERNAME  EMAIL_PASSWORD
#
# This image deliberately contains NO key file. .dockerignore keeps
# src/main/resources/keys out of the build context, and the build fails if a PEM
# ever reaches the jar. Supply the keypair through the environment.
#
# Run exactly ONE replica (H-7), and point APP_STORAGE_BASE_DIR /
# TRM_EXPORT_BASE_DIR at durable mounts — the defaults below live in the
# container's writable layer and are destroyed on every redeploy.

# ─── Stage 1: build ──────────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

# Dependencies first, so source edits do not invalidate the download layer.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# The image used to ship BOOT-INF/classes/keys/jwt-private.pem (1732 bytes):
# "COPY src ./src" fed Maven the developer's local keypair and it was packaged
# into the jar. .dockerignore now keeps it out of the context; this check makes
# the regression loud if that protection is ever removed (H-8).
RUN if jar tf target/*.jar | grep -qi '\.pem$'; then \
        echo "FATAL: key material found inside the jar — refusing to build:" >&2; \
        jar tf target/*.jar | grep -i '\.pem$' >&2; \
        exit 1; \
    fi

# ─── Stage 2: runtime ────────────────────────────────────────────────────────
# A JRE, not a JDK: production has no use for javac, jdb or jlink.
FROM eclipse-temurin:21-jre-jammy

# Run unprivileged. A fixed high uid/gid keeps ownership predictable across
# rebuilds when durable volumes are mounted.
RUN groupadd --system --gid 10001 app \
 && useradd  --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app

# --chown here rather than a later recursive chown: chown -R over /app rewrites the
# 85 MB jar's metadata, and copy-on-write then duplicates the whole file into a second
# layer. Measured on the first hardened build: it added 85.3 MB to the image.
COPY --from=build --chown=app:app /app/target/*.jar app.jar

# app.storage.base-dir and trm.export.base-dir default to ./uploads and ./exports,
# which resolve against this working directory. They must stay writable once
# privilege is dropped, or document upload and export fail at runtime.
RUN mkdir -p /app/uploads /app/exports \
 && chown app:app /app /app/uploads /app/exports

USER app

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]
