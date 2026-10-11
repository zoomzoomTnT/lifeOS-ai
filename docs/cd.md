# Continuous deploy

Merge **`main` → `release/x.y.z`** (current: `release/0.1.0`) to ship. CI on `main` tests and can publish the image; CD on `release/**` SSHs to the host and runs **`docker compose`** (Compose V2 plugin).

The server is **not** a git checkout. CD copies `docker-compose.yml` and `docker/ensure-hook-token.sh`, then `docker compose pull && up`. No token is baked into the GHCR image.

## Trigger

| Event | What happens |
|---|---|
| Push / merge to `release/**` | Maven verify → push GHCR → scp compose + ensure script → ensure hook token → `docker compose up` |
| Actions → CD → Run workflow | Same, or **skip_build** + **image_tag** to roll to an existing tag |

`workflow_dispatch` is a manual run of the same workflow (no git commit). Token logic is identical to a merge.

## Hook token (`OPENCLAW_HOOK_TOKEN`)

Opaque shared secret. OpenClaw does not expire it. CD **does not rotate** on deploy.

Every deploy, on the host:

1. If `~/lifeos/.env` already has a non-empty `OPENCLAW_HOOK_TOKEN`, reuse it.
2. Else mint `secrets.token_urlsafe(32)`, create/upsert `.env` (`chmod 600`).
3. `docker/openclaw-config.sh` binds CLI paths, then runs `openclaw config set` (echoed, token redacted):
   - `hooks.enabled true`
   - `hooks.path /hooks`
   - `hooks.token <same value>`
   - `hooks.mappings` → custom webhook `POST /hooks/life-os` (`action=agent`, deliver to `openclaw-weixin`)
   - `skills.load.watch true`
4. Script then `openclaw config get`s the non-secret keys so the CD log shows what landed.
5. Log line `openclaw config file=` must be `$OPENCLAW_HOME/openclaw.json`, never `$OPENCLAW_HOME/.openclaw/openclaw.json`.

No direct edits to `openclaw.json`. No `gateway restart`. Recreate the API container only on **first mint**.

Do **not** enable `plugins.entries.webhooks` for this. That plugin is TaskFlow CRUD and does not start an agent or send WeChat.

Does not invent `OPENCLAW_GATEWAY_TOKEN`, WeChat credentials, or `OPENCLAW_HOME`.

## `OPENCLAW_HOME`

Required in `~/lifeos/.env`. Absolute path only — this is the OpenClaw **state directory** (usually `~/.openclaw`), used as the compose volume. Compose binds `${OPENCLAW_HOME}` with **no default**. Set it once on the server; CD will not write it.

Do **not** export that value as `OPENCLAW_HOME` into the OpenClaw CLI. The CLI treats `OPENCLAW_HOME` as Unix `$HOME` and resolves config to `$OPENCLAW_HOME/.openclaw/openclaw.json`. CD instead exports:

- `OPENCLAW_STATE_DIR=$OPENCLAW_HOME`
- `OPENCLAW_CONFIG_PATH=$OPENCLAW_HOME/openclaw.json`

and unsets `OPENCLAW_HOME` before `openclaw config set|get|validate` and `openclaw skills list`.

## OpenClaw switch (`OPENCLAW_ENABLED`)

Repo **variable** (Settings → Secrets and variables → Actions → Variables), not a secret.
Unset or `true` = current behavior. `false` = the deploy skips `ensure-hook-token.sh`,
`openclaw config validate`, the `skill-sync` run and `openclaw skills list`; the value is written to
`~/lifeos/.env` and passed to the container, where it turns off every OpenClaw integration in the app.
`OPENCLAW_HOME` / `OPENCLAW_HOOK_TOKEN` are not needed then. Re-run CD after changing it.

## Secrets

| Name | Use |
|---|---|
| `SERVER_IP` | Host |
| `SERVER_UN` | SSH user |
| `SERVER_PK` | Private key PEM / OpenSSH |
| `TURSO_AUTH_TOKEN` | Turso token for the production database |
| `LIFE_KEY_PEPPER` | **Required.** HMAC key for API key hashes (`openssl rand -base64 48`). CD fails early without it and writes it to `~/lifeos/.env` (mode 600). See [auth.md](auth.md#cutover) |

Repo **variable** `LIFE_DOMAIN` (optional): HTTPS host for Caddy (compose profile `proxy`, ports
80/443, Let's Encrypt) and the passkey RP ID. After deploy CD checks anonymous `/api` and
`/actuator/info` → 401.

Repo **variable** `TURSO_DATABASE_URL` (or a secret of that name): the production database URL.
The app and compose file have no default; if neither the variable/secret nor `~/lifeos/.env` sets
it, the deploy fails before changing anything on the host. See [turso.md](turso.md).

Optional host env `DEPLOY_DIR` (default `$HOME/lifeos`).

## Server layout (no git)

```
$HOME/lifeos/
  compose.yaml
  ensure-hook-token.sh
  docker/Caddyfile     # used when LIFE_DOMAIN is set
  .env                 # mode 600; CD upserts LIFE_KEY_PEPPER, LIFE_DOMAIN, OPENCLAW_*
  data/life.db
  data/backups/
```

Host needs: Docker Engine + Compose V2 plugin, `openclaw` on PATH for the SSH user.

## Server prerequisites

A fresh host needs, for the SSH user CD logs in as:

- Docker Engine running, with the **Compose V2 plugin** (`docker compose version` works; not legacy `docker-compose`)
- the user can talk to the daemon without sudo (`docker info` works; user in the `docker` group)
- `curl` (health check)

CD preflight prints `preflight: ...` lines and fails with an `::error::` naming the missing piece.

Alibaba Cloud Linux 3 / CentOS / RHEL-like:

```bash
sudo dnf -y install dnf-plugins-core curl
sudo dnf -y install dnf-plugin-releasever-adapter --repo alinux3-plus   # Alibaba Cloud Linux 3 only
sudo dnf config-manager --add-repo https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo
sudo dnf -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"   # then log out/in
docker compose version && docker info >/dev/null && echo OK
```

Ubuntu / Debian:

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"   # then log out/in
docker compose version && docker info >/dev/null && echo OK
```
