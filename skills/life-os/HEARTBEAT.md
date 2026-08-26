# HEARTBEAT.md

Periodic OpenClaw model heartbeats are **off**. `agents.defaults.heartbeat.every = "0m"`.

The Spring Boot app is the clock (`ProactiveCronService`). It POSTs `/hooks/agent` only when `GET /api/ops/should-wake` is true. That isolated turn should follow `skills/life-os/references/proactive.md`.

If a harness still injects a heartbeat turn:

```
GET $LIFE_API_BASE/api/ops/should-wake
If wake=false, reply HEARTBEAT_OK and stop. Do not call other tools. Do not use vision.
```

Copy this file to the OpenClaw workspace root:

```bash
cp skills/life-os/HEARTBEAT.md ~/.openclaw/workspace/HEARTBEAT.md
```
