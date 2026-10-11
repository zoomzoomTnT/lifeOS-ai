#!/bin/sh
# lifeos-admin: owner credential tool (API keys for Grok Bot, first-passkey login link).
# Runs inside the api container with the app's env (Turso + LIFE_KEY_PEPPER):
#   docker exec -it lifeos-api lifeos-admin help
# Small separate JVM (~60-100 MB for a few seconds); the running API is not touched.
exec java -Xmx96m -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xshare:auto \
  -Dloader.main=com.lifeos.cli.LifeAdmin \
  -cp "${LIFE_APP_JAR:-/app/app.jar}" org.springframework.boot.loader.launch.PropertiesLauncher "$@"
