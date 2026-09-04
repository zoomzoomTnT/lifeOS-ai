# OpenClaw webhooks (config: `hooks.*`)

The product name in OpenClaw docs is **Webhooks**. The live config keys and
HTTP prefix are still `hooks.*` / `/hooks`. Do not invent `webhooks.path`.

Official reference: [Webhooks](https://docs.openclaw.ai/automation/webhook)
and [configuration-reference § hooks](https://docs.openclaw.ai/gateway/configuration-reference).

## Three different “hook” things

| Thing | What it is | Life OS uses it? |
|---|---|---|
| **Webhook ingress** (`hooks.enabled`, `hooks.path`, `hooks.token`) | Gateway HTTP endpoints for external triggers | **Yes** — Spring wakes the skill |
| **`openclaw hooks` CLI / `hooks.internal`** | In-process `HOOK.md` handlers (`command:new`, session events) | No |
| **`openclaw webhooks` CLI** | Gmail Pub/Sub helpers; they still write `hooks.gmail` + `hooks.enabled` | No |

## Path

`hooks.path` is the **base prefix**, not a file on disk.

| Rule | Value |
|---|---|
| Default (and what CD sets) | `/hooks` |
| Forbidden | `/` (OpenClaw rejects a root prefix) |
| Normalization | leading `/` added, trailing `/` stripped |
| Override | host env `OPENCLAW_HOOKS_PATH` before `ensure-hook-token.sh` |

CD: `openclaw config set hooks.path "/hooks"` then `openclaw config get hooks.path`.
Hot config; no Gateway restart.

## Routes Life OS cares about

Assume `hooks.path=/hooks` and Gateway `http://127.0.0.1:18789`.

| Method | URL | When |
|---|---|---|
| `POST` | `/hooks/agent` | Spring proactive: isolated agent turn, `deliver` to `openclaw-weixin` |
| `POST` | `/hooks/wake` | Main-session heartbeat nudge. **Unused** — `heartbeat.every=0m` |
| `POST` | `/hooks/<name>` | Only if `hooks.mappings` defines `match.path=<name>` |

`/hooks` itself has no action.

Auth (header only; `?token=` is 400):

- `Authorization: Bearer <hooks.token>` (what the API sends)
- or `x-openclaw-token: <hooks.token>`

`hooks.token` must not equal `gateway.auth.token` / `OPENCLAW_GATEWAY_TOKEN`.

## Why `/hooks/agent`, not `/hooks/wake`

`/hooks/wake` queues a system line on the **main** session and optionally
fires a heartbeat. Life OS turned heartbeat off so a due memo does not share
chat history or wait for the next pulse.

`/hooks/agent` starts an isolated turn:

```json
{
  "message": "…",
  "name": "life-os-proactive",
  "sessionMode": "isolated",
  "deliver": true,
  "channel": "openclaw-weixin",
  "to": "<people.handle>",
  "timeoutSeconds": 90
}
```

`to` comes from the DB at fire time, so a mapping under `/hooks/life-os`
would still need the same body. Built-in `/hooks/agent` is the route.

Recommended Gateway policy (CD also sets these):

```json5
{
  hooks: {
    enabled: true,
    path: "/hooks",
    token: "${OPENCLAW_HOOK_TOKEN}",
    defaultSessionKey: "hook:life-os",
    allowRequestSessionKey: false
  }
}
```

## Java side

`OpenClawClient` posts to:

`{OPENCLAW_GATEWAY}{life.openclaw.hooks-path}/agent`

Defaults: `http://localhost:18789` + `/hooks` → `http://localhost:18789/hooks/agent`.

Keep `life.openclaw.hooks-path` equal to `hooks.path` on the Gateway.
If you change the prefix on the host, set both `OPENCLAW_HOOKS_PATH` (CD) and
`LIFE_OPENCLAW_HOOKS_PATH` (compose → Spring).
