# life-os heartbeat policy (merge into workspace HEARTBEAT.md)

**Disable the periodic OpenClaw heartbeat.** `agents.defaults.heartbeat.every = "0m"`.

The Spring Boot app is the scheduler (`ProactiveCronService`). It POSTs
`/hooks/agent` only when `GET /api/ops/should-wake` is true.

If a heartbeat entry must remain (harness requirement):

```
GET $LIFE_API_BASE/api/ops/should-wake
If wake=false, reply HEARTBEAT_OK and stop. Do not call other tools. Do not use vision.
```
