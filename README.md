# lifeOS-ai

[![CI](https://github.com/zoomzoomTnT/lifeOS-ai/actions/workflows/ci.yml/badge.svg)](https://github.com/zoomzoomTnT/lifeOS-ai/actions/workflows/ci.yml)

WeChat / OpenClaw 生活台账：一份 SQLite、Spring Boot REST、一层 OpenClaw skill。

Repo: [zoomzoomTnT/lifeOS-ai](https://github.com/zoomzoomTnT/lifeOS-ai)  
Image: `ghcr.io/zoomzoomtnt/lifeos-ai:latest`

记账 / 小票、冰箱、备忘（主动提醒）、持仓（试用）。

---

## 安装（换机器按这份来）

这些只做一次。漏任何一项，主动提醒或日志入库会静默失败。

**CD 还没做。** GitHub Actions 只测 + 推 GHCR 镜像，不会 SSH 进你的机器。更新 skill / API 靠你在这台机上 `compose pull` + `skill-sync`（见文末「日常更新」）。

### 0. 机器上先有的东西

- Docker + Docker Compose
- 已在跑的 **OpenClaw Gateway**（默认 `localhost:18789`）和 **openclaw-weixin**
- 本机 clone 本仓库（compose 文件、`.env` 放这里）

### 1. 环境变量

```bash
git clone https://github.com/zoomzoomTnT/lifeOS-ai.git
cd lifeOS-ai
cp env.example .env
```

生成钩子 token（**不要**复用 Gateway token）：

```bash
python3 -c "import secrets; print(secrets.token_urlsafe(32))"
```

写入 `.env`（不要 commit `.env`）：

| 变量 | 必填 | 作用 |
|---|---|---|
| `OPENCLAW_HOOK_TOKEN` | **是** | Spring 调 `POST /hooks/agent`。必须等于 `openclaw.json` 的 `hooks.token` |
| `OPENCLAW_GATEWAY_TOKEN` | **是** | Gateway 自身 auth |
| `OPENCLAW_GATEWAY` | Docker 默认即可 | compose 默认 `http://host.docker.internal:18789` |
| `LIFE_OPENCLAW_WAKE` | 默认 `true` | `false` = 只扫库、不叫醒模型（CI 用） |
| `LIFE_WEIXIN_CHANNEL` | 默认 `openclaw-weixin` | `/hooks/agent` 的 `channel` |
| `LIFE_PROACTIVE_MODEL` | 可选 | 主动提醒用的便宜文本模型；空 = Gateway 默认 |
| `LIFE_API_PORT` | 默认 `8787` | 宿主机端口 |
| `LIFE_DATA` | 默认 `./data` | `life.db` 目录，**备份这个文件夹** |
| `OPENCLAW_HOME` | 默认 `$HOME/.openclaw` | 挂进容器：API 只读吃 jsonl；`skill-sync` 可写 skill |
| `LIFE_OPENCLAW_FILE_LOG` | 可选 | Gateway JSONL 目录（如 `/tmp/openclaw`）→ `app_logs` |
| `LIFE_API_BASE` | skill 侧 | `http://localhost:8787` |
| `LIFE_IMAGE` | 可选 | 默认 `ghcr.io/zoomzoomtnt/lifeos-ai:latest` |

Gateway 进程也要看得到这两个 token（写进它的 systemd/env 或 shell profile）：

```bash
export OPENCLAW_HOOK_TOKEN='和 .env 相同'
export OPENCLAW_GATEWAY_TOKEN='原来的 gateway token'
```

### 2. OpenClaw 钩子（`~/.openclaw/openclaw.json`）

仓库模板：[openclaw/openclaw.json](openclaw/openclaw.json)（无密钥）。**合并**进你现有的配置，微信插件 / 模型 key 留在本地。

必须有：

```json5
{
  agents: {
    defaults: {
      workspace: "~/.openclaw/workspace",
      heartbeat: { every: "0m", target: "none" }   // 关掉 30 分钟模型心跳
    }
  },
  gateway: {
    port: 18789,
    bind: "localhost",
    auth: { token: "${OPENCLAW_GATEWAY_TOKEN}" }
  },
  hooks: {
    enabled: true,
    token: "${OPENCLAW_HOOK_TOKEN}",   // 和 .env 同一个
    path: "/hooks"
  }
}
```

改完重启 Gateway：`openclaw gateway restart`。

谁叫醒谁：

```
Spring @Scheduled 每分钟（只跑 SQL）
    wake=false → 睡觉，$0
    wake=true 且没锁
    ▼
POST $OPENCLAW_GATEWAY/hooks/agent
    Authorization: Bearer $OPENCLAW_HOOK_TOKEN
    { message, sessionMode: isolated, deliver, channel: openclaw-weixin, to }
    ▼
OpenClaw 跑 life-os skill → 微信
```

先测钩子（`to` 换成你的微信 peer id）：

```bash
curl -sS -X POST http://localhost:18789/hooks/agent \
  -H "Authorization: Bearer $OPENCLAW_HOOK_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "message": "life-os 钩子连通测试。只回一句「钩子 OK」。",
    "name": "life-os-proactive",
    "sessionMode": "isolated",
    "deliver": true,
    "channel": "openclaw-weixin",
    "to": "你的微信 peer id",
    "timeoutSeconds": 90
  }'
```

要点：`Authorization: Bearer …`（不要 `?token=`）；`deliver: true` 时 `channel` 和 `to` 必须成对；`sessionMode: isolated` 不带聊天历史。

| 现象 | 原因 |
|---|---|
| 401 | `.env` 和 `hooks.token` 不一致，或 Gateway 没重启 |
| 404 `/hooks/agent` | `hooks.enabled` 不是 true |
| 400 | `channel` / `to` 只填了一个 |
| 容器连不上 | 要用 `host.docker.internal:18789`，Gateway 听 `localhost:18789` |

官方字段：[Automations / hooks](https://docs.openclaw.ai/automation/cron-jobs)。

### 3. Skill 自动加载

OpenClaw **会扫目录**，不必在配置里逐个登记名字。有 `SKILL.md` 就会加载。优先级最高：

`<workspace>/skills/*/SKILL.md` → 默认 `~/.openclaw/workspace/skills/life-os/SKILL.md`

**推荐：用 Docker 从镜像同步**（skill 已打进 GHCR，不必手抄）：

```bash
docker compose up -d --build
docker compose --profile sync run --rm skill-sync
```

写入：

- `$OPENCLAW_HOME/workspace/skills/life-os/`（含 `SKILL.md` + `references/`）
- `$OPENCLAW_HOME/workspace/HEARTBEAT.md`

然后：

```bash
openclaw skills list    # 应有 life-os
```

`skills.load.watch` 默认 `true`，改 `SKILL.md` 下一轮对话会刷新。

skill 调 API：`LIFE_API_BASE=http://localhost:8787`，请求头 `X-Life-Handle: <微信 peer id>`。  
把 `people.handle` 从占位 `owner` 改成真实微信 id。时区默认 **Asia/Tokyo**；期权 cron 用 **America/New_York**。

自动加载失败时：

| 现象 | 原因 |
|---|---|
| `skills list` 没有 life-os | 路径不是 `.../skills/life-os/SKILL.md` |
| 有文件但不进 prompt | `agents.defaults.skills` 写成了白名单且没写 `life-os`（删掉该数组即恢复全扫） |
| 两个 skill 抢名 | 删掉旧目录 `rm -rf ~/.openclaw/workspace/skills/life-os-skills` |
| `enabled: false` | `skills.entries.life-os.enabled` 被关 |

可选：不 sync、让 OpenClaw 直接读 git clone（`extraDirs` 优先级最低）：

```json5
{
  skills: {
    load: {
      extraDirs: ["/home/YOU/lifeOS-ai/skills"],
      watch: true
    },
    entries: { "life-os": { enabled: true } }
  }
}
```

### 4. 启动 API

```bash
docker compose up -d --build
docker compose --profile sync run --rm skill-sync
curl -fsS http://localhost:8787/actuator/health
curl -fsS http://localhost:8787/api/ops/should-wake
```

- API: http://localhost:8787/actuator/health
- Actuator: http://localhost:8787/actuator (`/health`, `/db`, `/info`, `/metrics`, `/scheduledtasks`)
- Swagger UI: http://localhost:8787/swagger-ui.html
- Ops: http://localhost:8787/ops
- DB：`$LIFE_DATA/life.db`（默认 `./data/life.db`），备份这个文件

```bash
docker compose logs -f api
docker compose down
```

只用已发布镜像（不本地 build）：

```bash
LIFE_IMAGE=ghcr.io/zoomzoomtnt/lifeos-ai:latest docker compose up -d
docker compose --profile sync run --rm skill-sync
```

### 5. 日志入库

Java 每 2 分钟扫描 `OPENCLAW_HOME`：

| 磁盘 | 表 | 隐私 |
|---|---|---|
| `agents/*/sessions/*.jsonl`、`transcripts/**/transcript.jsonl`、`**/events.jsonl` | **`ai_session_logs`** | **对话**，默认 API 不返回 `content` / `raw_json` |
| Gateway JSONL（`LIFE_OPENCLAW_FILE_LOG`）+ 本进程事件 | **`app_logs`** | 应用日志，无对话 |
| HTTP / token 花费 | `http_requests` / `ai_calls` | 无完整 prompt |

`source` + `occurred_at` 区分来源。`/ops` 只显示条数，不显示对话。

```bash
curl -s -X POST http://localhost:8787/api/ops/logs/ingest
curl -s http://localhost:8787/api/ops/logs/sessions                  # 无正文
curl -s 'http://localhost:8787/api/ops/logs/sessions?include_content=true'  # 隐私
```

### 6. 自检清单

- [ ] `.env` 里 `OPENCLAW_HOOK_TOKEN` 非空，且等于 `hooks.token`
- [ ] `OPENCLAW_GATEWAY_TOKEN` 已给 Gateway 进程
- [ ] heartbeat 已是 `0m`，没有 30 分钟模型心跳
- [ ] `openclaw gateway restart` 之后钩子 curl 不是 401/404
- [ ] `skill-sync` 之后存在 `~/.openclaw/workspace/skills/life-os/SKILL.md`
- [ ] `openclaw skills list` 有 `life-os`
- [ ] `curl /actuator/health` 且 `"status":"UP"`
- [ ] `POST /api/ops/proactive/run` 没到期时 `wake=false`；到期时 Gateway 出现 `/hooks/agent`
- [ ] `POST /api/ops/logs/ingest` 的 `session_rows` 会涨
- [ ] `./data/life.db` 纳入备份

---

## 日常更新（CD 以后再做）

现在 **没有** 自动部署到你的 OpenClaw 机器。CI 在 `main` 上：测 schema / Maven / compose smoke，再推 `ghcr.io/zoomzoomtnt/lifeos-ai:latest`。

你这台机更新：

```bash
cd lifeOS-ai
docker compose pull
docker compose up -d
docker compose --profile sync run --rm skill-sync
openclaw skills list
```

以后要做的 CD（还没写）：自托管 runner 或 Watchtower 在这台机跑上面四行。不要把 SSH 私钥提交进仓库。

若 GHCR 包第一次是 private：GitHub → Packages → `lifeos-ai` → 改成 Public。

---

## Layout

```
.
├── env.example                 # install 环境变量模板
├── Dockerfile                  # API + /opt/life-os-skill
├── docker-compose.local.yml    # local profile：./local/life.db，不叫醒微信
├── local/                      # 调试用 db 目录（main 不提交 .db；分支 local 才跟踪）
├── docker/sync-skill.sh
├── openclaw/openclaw.json      # heartbeat 0m + hooks（无密钥）
├── schema/schema.sql
├── schema/migrations/          # 0002_ops, 0003_logs
├── docs/api.md
├── docs/logging.md
├── app/                        # Spring Boot 3 + Java 21
├── skills/life-os/             # 完整 OpenClaw skill
└── .github/workflows/ci.yml    # 测试 + 推镜像，不部署到你的机器
```

## Maven（不用 Docker 时）

```bash
export LIFE_DB=~/.openclaw/workspace/data/life.db
export LIFE_OPENCLAW_HOME=~/.openclaw
export OPENCLAW_HOOK_TOKEN=...
cd app && mvn spring-boot:run
```

skill 仍建议 `docker compose --profile sync run --rm skill-sync`，或手动把 `skills/life-os` 放到 workspace。

## 本地调试（`local` profile）

Spring profile `local` 把库指到 **仓库里的** `local/life.db`，并且 **关掉** OpenClaw 主动叫醒（避免 debug 时给微信发消息）。

`main` **不提交** `.db`（对话在库里）。调试库放在 git 分支 **`local`** 上（`git add -f local/life.db`）。

### 从 server 拷库

```bash
git checkout local
git pull

# 路径按你 server 上 compose 的 LIFE_DATA，默认是仓库 ./data/life.db
scp you@SERVER:~/lifeOS-ai/data/life.db ./local/life.db
# 或
# scp you@SERVER:~/.openclaw/workspace/data/life.db ./local/life.db

# 可选：把这份 dump 记在 local 分支，不要 merge 进 main
git add -f local/life.db
git commit -m "Refresh local debug db from server"
```

WAL 文件一并拷（若 server 上 API 还开着，先停再拷，避免半截库）：

```bash
scp you@SERVER:~/lifeOS-ai/data/life.db* ./local/
```

### 启动（Maven）

在 `app/` 下跑，这样默认路径 `../local/life.db` 才对：

```bash
cd app
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

或任意目录：

```bash
LIFE_DB=/abs/path/lifeOS-ai/local/life.db \
  mvn -f app/pom.xml spring-boot:run -Dspring-boot.run.profiles=local
```

### 启动（Docker）

```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --build
curl -fsS http://localhost:8787/actuator/health
```

`docker-compose.local.yml` 把 `./local` 挂到容器 `/data`，`LIFE_OPENCLAW_WAKE=false`。

### 不要做的事

- 不要把 `local/life.db` merge / cherry-pick 进 `main`
- 不要在 `local` profile 下开 `LIFE_OPENCLAW_WAKE=true` 对着生产微信试
- 拷库前尽量 `docker compose stop api`，否则 WAL 对不上


## Design

- `schema.sql` 是可执行 schema。领域规则在 markdown。
- 分层：`web`（typed DTO）→ `service` 接口 → `repo` 接口 → `repo.jdbc`。Service 不再写 SQL。
- Bean 注入用 Lombok `@RequiredArgsConstructor`（一行，不手写构造器）。
- CHECK 值用 enum（`eaten` / `discarded` / `in_stock`…），JSON 存库都是小写字符串。
- 写入只走 Java REST。skill 只做视觉 / 意图 / 中文。
- 主动提醒：Spring cron 扫库，到期才打 `/hooks/agent`。
- 对话在 `ai_session_logs`，应用日志在 `app_logs`。
- 校验失败走 `{error, message, details}`（`ApiExceptionHandler`）。

