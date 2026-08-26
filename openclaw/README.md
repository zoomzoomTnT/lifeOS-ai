# OpenClaw config (versioned)

`openclaw.json` in this folder is the life-os policy:

- **heartbeat.every = 0m** — no 30-minute model wake
- **hooks.enabled** — Spring Boot wakes the skill only when SQLite says something is due
- secrets via env (`OPENCLAW_GATEWAY_TOKEN`, `OPENCLAW_HOOK_TOKEN`), never committed

Weixin / plugin credentials stay in your existing `~/.openclaw/openclaw.json` (or `openclaw.local.json`, gitignored). Merge the `agents.defaults.heartbeat` and `hooks` blocks into that file.

Install the skill:

```bash
cp -R skills/life-os ~/.openclaw/workspace/skills/life-os
cp skills/life-os/HEARTBEAT.md ~/.openclaw/workspace/HEARTBEAT.md
```


Generate a hook token and put it in the environment Spring Boot sees:

```bash
export OPENCLAW_HOOK_TOKEN=...
export OPENCLAW_GATEWAY=http://127.0.0.1:18789
```

Docker Compose uses `host.docker.internal:18789` so the API container can reach the host gateway.

## Who wakes whom

```
Spring @Scheduled (every minute, SQL only)
    │  wake=false → log, sleep, $0
    │  wake=true  and not locked
    ▼
POST $OPENCLAW_GATEWAY/hooks/agent
    Authorization: Bearer $OPENCLAW_HOOK_TOKEN
    { message, sessionMode: isolated, deliver, channel: openclaw-weixin, to }
    ▼
OpenClaw runs life-os skill once → WeChat
```

Verify:

```bash
curl -s http://127.0.0.1:8787/api/ops/should-wake
curl -s -X POST http://127.0.0.1:8787/api/ops/proactive/run
```
