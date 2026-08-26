# life-os heartbeat policy (merge into workspace HEARTBEAT.md)

**Disable the periodic OpenClaw heartbeat for this agent.**

Due work is delivered by OpenClaw *automations* created when memos are inserted.
The Spring Boot app scans SQLite every 15 minutes with no model.

If a heartbeat entry must remain (harness requirement), replace the body with:

```
GET $LIFE_API_BASE/api/ops/should-wake
If wake=false, reply HEARTBEAT_OK and stop. Do not call other tools. Do not use vision.
If wake=true, follow the JSON instruction (≤2 WeChat messages).
```

Interval if forced: ≥ 6 hours, not 30 minutes. activeHours 08:00-22:00 Asia/Tokyo.
