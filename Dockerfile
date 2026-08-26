# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY app/pom.xml .
COPY app/src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
LABEL org.opencontainers.image.source="https://github.com/zoomzoomTnT/lifeOS-ai" \
      org.opencontainers.image.title="lifeOS-ai" \
      org.opencontainers.image.description="Life OS REST API (SQLite + Spring Boot)"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /data /app

COPY --from=build /build/target/life-os-app.jar /app/app.jar
COPY skills/life-os /opt/life-os-skill
COPY docker/sync-skill.sh /app/sync-skill.sh
RUN chmod +x /app/sync-skill.sh

WORKDIR /app

ENV LIFE_DB=/data/life.db \
    LIFE_API_PORT=8787 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

EXPOSE 8787
VOLUME /data

HEALTHCHECK --interval=15s --timeout=3s --start-period=25s --retries=8 \
  CMD curl -fsS http://127.0.0.1:8787/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
