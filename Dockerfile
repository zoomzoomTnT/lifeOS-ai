# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY app/pom.xml .
COPY app/src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
LABEL org.opencontainers.image.source="https://github.com/zoomzoomTnT/lifeOS-ai" \
      org.opencontainers.image.title="lifeOS-ai" \
      org.opencontainers.image.description="Life OS REST API (Turso/libSQL + Spring Boot)" \
      lifeos.auth="1"
# lifeos.auth=1: this image enforces API authentication (docs/auth.md). CD only starts the public
# HTTPS proxy (Caddy) for images that carry it, so a rollback to a pre-auth image stays loopback-only.

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app

COPY --from=build /build/target/life-os-app.jar /app/app.jar
COPY skills/life-os /opt/life-os-skill
COPY docker/sync-skill.sh /app/sync-skill.sh
COPY docker/lifeos-admin.sh /usr/local/bin/lifeos-admin
RUN chmod +x /app/sync-skill.sh /usr/local/bin/lifeos-admin

WORKDIR /app

# Database: pass TURSO_DATABASE_URL and TURSO_AUTH_TOKEN at runtime (never bake a token in).
ENV LIFE_API_PORT=8787 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

EXPOSE 8787

HEALTHCHECK --interval=15s --timeout=3s --start-period=25s --retries=8 \
  CMD curl -fsS http://localhost:8787/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
