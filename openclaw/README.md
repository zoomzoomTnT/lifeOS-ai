# OpenClaw config (versioned)

安装、webhook 入口、skill 自动加载、Docker `skill-sync`：**看仓库根 [README.md](../README.md)** 和 [docs/webhooks.md](../docs/webhooks.md)。

本目录的 `openclaw.json` 是无密钥策略模板：

- `heartbeat.every = 0m`
- webhook ingress: `hooks.enabled` + `hooks.path = /hooks` + `hooks.token = ${OPENCLAW_HOOK_TOKEN}`

OpenClaw 文档把这项功能叫 **Webhooks**。配置键是 `hooks.*`，HTTP 前缀默认 `/hooks`。没有 `webhooks.path`。

Life OS 只打 `POST /hooks/agent`（隔离会话 + 微信投递），不用 `POST /hooks/wake`（主会话心跳；已关）。

合并进 `~/.openclaw/openclaw.json`，微信插件密钥留在本地（`openclaw.local.json` 已 gitignore）。
