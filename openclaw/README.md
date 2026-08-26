# OpenClaw config (versioned)

安装、钩子、skill 自动加载、Docker `skill-sync`：**看仓库根 [README.md](../README.md)**。CD 还没做。

本目录的 `openclaw.json` 是无密钥策略模板：

- `heartbeat.every = 0m`
- `hooks.enabled` + `hooks.token = ${OPENCLAW_HOOK_TOKEN}`

合并进 `~/.openclaw/openclaw.json`，微信插件密钥留在本地（`openclaw.local.json` 已 gitignore）。
