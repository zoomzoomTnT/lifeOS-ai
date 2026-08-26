# lifeOS-ai

[![CI](https://github.com/zoomzoomTnT/lifeOS-ai/actions/workflows/ci.yml/badge.svg)](https://github.com/zoomzoomTnT/lifeOS-ai/actions/workflows/ci.yml)

WeChat / OpenClaw Life OS: one SQLite file, a Spring Boot REST API, and a thin AI skill.

Repo: [zoomzoomTnT/lifeOS-ai](https://github.com/zoomzoomTnT/lifeOS-ai)  
Image: `ghcr.io/zoomzoomtnt/lifeos-ai:latest`

---

## 安装时必须配置（勿忘）

这些只在 install / 换机器时做一次。漏任何一项，主动提醒或日志入库会静默失败。

### 0. 机器上先有的东西

- Docker + Docker Compose
- 已在跑的 **OpenClaw Gateway**（默认 `127.0.0.1:18789`）和 **openclaw-weixin** 插件
- Java 21（只有不用 Docker 时才需要）

### 1. 环境变量（复制 `env.example` → `.env`，不要提交 `.env`）

| 变量 | 必填 | 作用 |
|---|---|---|
| `OPENCLAW_HOOK_TOKEN` | **是** | Spring 调 `POST /hooks/agent` 叫醒 skill。和 `openclaw.json` 里 `hooks.token` 相同 |
| `OPENCLAW_GATEWAY_TOKEN` | **是** | Gateway 自身 auth（写在 OpenClaw 配置，可用 `${OPENCLAW_GATEWAY_TOKEN}`） |
| `OPENCLAW_GATEWAY` | Docker 下默认即可 | compose 默认 `http://host.docker.internal:18789` |
| `LIFE_OPENCLAW_WAKE` | 默认 `true` | `false` 则 Java 只扫库、不叫醒模型（CI 用） |
| `LIFE_WEIXIN_CHANNEL` | 默认 `openclaw-weixin` | `/hooks/agent` 的 `channel` |
| `LIFE_PROACTIVE_MODEL` | 可选 | 主动提醒用的便宜文本模型；空 = Gateway 默认 |
| `LIFE_API_PORT` | 默认 `8787` | 宿主机端口 |
| `LIFE_DATA` | 默认 `./data` | `life.db` 所在目录，**备份这个文件夹** |
| `OPENCLAW_HOME` | 默认 `$HOME/.openclaw` | 只读挂进容器 `/openclaw`，用来吃 session jsonl |
| `LIFE_OPENCLAW_FILE_LOG` | 可选 | OpenClaw gateway JSONL 目录（如 `/tmp/openclaw`）→ `app_logs` |
| `LIFE_API_BASE` | skill 侧 | `http://127.0.0.1:8787` |

```bash
cp env.example .env
# 填 OPENCLAW_HOOK_TOKEN / OPENCLAW_GATEWAY_TOKEN
```

### 2. OpenClaw 配置（`openclaw/openclaw.json`）

把仓库里这两段 **合并** 进 `~/.openclaw/openclaw.json`（微信插件的 token / 账号留在本地，不要 commit）：

1. `agents.defaults.heartbeat.every = "0m"` — **关掉 30 分钟模型心跳**
2. `hooks.enabled = true`，`hooks.token = "${OPENCLAW_HOOK_TOKEN}"`
3. workspace 里的 `HEARTBEAT.md` 用 `skills/life-os/HEARTBEAT.md` 覆盖

微信插件、模型 API key、Gateway bind 继续用你现有的。仓库这份 **不含密钥**。

### 3. Skill

```bash
cp -R skills/life-os ~/.openclaw/workspace/skills/life-os
cp skills/life-os/HEARTBEAT.md ~/.openclaw/workspace/HEARTBEAT.md
# skill 调 API 时带:
#   LIFE_API_BASE=http://127.0.0.1:8787
#   X-Life-Handle: <你的微信 peer id>
```

把 `people.handle` 从占位 `owner` 改成真实微信 id（API `PUT /api/people/me` 或第一次带 header 会自动建 member）。时区默认 **Asia/Tokyo**；期权 cron 用 **America/New_York**。

### 4. 启动

```bash
docker compose up -d --build
curl -fsS http://127.0.0.1:8787/api/health
curl -fsS http://127.0.0.1:8787/api/ops/should-wake
```

Ops 面板: http://127.0.0.1:8787/ops  

`life.db` 路径: `$LIFE_DATA/life.db`（compose 下是 `./data/life.db`）。

### 5. 日志入库（session trajectory → SQLite）

Java 每 2 分钟扫描 `OPENCLAW_HOME`：

| 磁盘 | 表 | 隐私 |
|---|---|---|
| `agents/*/sessions/*.jsonl`、`transcripts/**/transcript.jsonl`、`**/events.jsonl` | **`ai_session_logs`** | **对话**，默认 API 不返回 `content` / `raw_json` |
| Gateway JSONL（`LIFE_OPENCLAW_FILE_LOG`）+ 本进程事件 | **`app_logs`** | 应用日志，无对话 |
| HTTP / token 花费 | `http_requests` / `ai_calls` | 无完整 prompt |

`source` + `occurred_at` 区分来源。手动：`POST /api/ops/logs/ingest`  
看会话元数据：`GET /api/ops/logs/sessions`  
看正文（隐私）：`GET /api/ops/logs/sessions?include_content=true`

### 6. 自检清单

- [ ] `.env` 里 `OPENCLAW_HOOK_TOKEN` 非空，且等于 OpenClaw `hooks.token`
- [ ] heartbeat 已是 `0m`，workspace 没有 30m 模型心跳
- [ ] skill 已拷到 `~/.openclaw/workspace/skills/life-os`
- [ ] `curl /api/health` 且 `db=ok`
- [ ] `POST /api/ops/proactive/run` 在没到期时 `wake=false`；到期时 Gateway 日志出现 `/hooks/agent`
- [ ] `OPENCLAW_HOME` 挂载成功，`POST /api/ops/logs/ingest` 的 `session_rows` 会涨
- [ ] `./data/life.db` 纳入你的备份（Time Machine / rsync / 拷走）

---

## One-click: Docker Compose

```bash
git clone https://github.com/zoomzoomTnT/lifeOS-ai.git
cd lifeOS-ai
cp env.example .env   # 填 token
docker compose up -d --build
```

```bash
docker compose logs -f api
docker compose down
```

Published image:

```bash
LIFE_IMAGE=ghcr.io/zoomzoomtnt/lifeos-ai:latest docker compose up -d
```

时钟在 **Spring cron**（每分钟 SQL）。只有 memo 到期才 `POST /hooks/agent`。配置见 [`openclaw/openclaw.json`](openclaw/openclaw.json)。

## Layout

```
.
├── env.example                 # install 环境变量模板
├── Dockerfile
├── docker-compose.yml
├── openclaw/openclaw.json      # heartbeat 0m + hooks（无密钥）
├── schema/schema.sql
├── schema/migrations/          # 0002_ops, 0003_logs
├── docs/api.md
├── docs/logging.md
├── app/                        # Spring Boot 3 + Java 21
├── skills/life-os/             # OpenClaw skill（完整包）
└── .github/workflows/ci.yml
```

## CI

On `main` and pull requests:

1. Schema copies must match; apply to a fresh SQLite db; apply `0002_ops` + `0003_logs` onto an old db
2. Maven `verify` (Java 21); upload jar on `main`
3. Docker build + compose smoke: health, `/ops`, AI pricing, should-wake, jsonl ingest
4. On `main` push, publish `ghcr.io/zoomzoomtnt/lifeos-ai` (`latest` + `sha-*`)

If the GHCR package is private on first publish: GitHub → Packages → `lifeos-ai` → Package settings → Change visibility → Public.

## Maven (without Docker)

```bash
export LIFE_DB=~/.openclaw/workspace/data/life.db
export LIFE_OPENCLAW_HOME=~/.openclaw
export OPENCLAW_HOOK_TOKEN=...
cd app && mvn spring-boot:run
```

Skill: `cp -R skills/life-os ~/.openclaw/workspace/skills/life-os` and `LIFE_API_BASE=http://127.0.0.1:8787`.

## Design (2026-08-26)

- `schema.sql` is the only executable schema. Domain rules stay in markdown.
- All writes go through Java REST.
- AI skill: vision + intent + Chinese copy. App owns fingerprint, fridge, due, backup, log ingest.
- Conversation transcripts stay in `ai_session_logs`, never in `app_logs`.
