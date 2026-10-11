package com.lifeos.cli;

import com.lifeos.db.libsql.LibSqlHttpDriver;
import com.lifeos.security.ApiKeyService;
import com.lifeos.security.ApiKeyStore;
import com.lifeos.security.AuthProperties;
import com.lifeos.security.ClientKind;
import com.lifeos.security.KeyHasher;
import com.lifeos.security.OwnerAccess;
import com.lifeos.security.Scopes;
import com.lifeos.security.SecurityEventLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.io.PrintStream;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * lifeos-admin: the owner's one-shot tool for credentials, run ON the server (it needs the same
 * env as the app: TURSO_DATABASE_URL, TURSO_AUTH_TOKEN, LIFE_KEY_PEPPER). No HTTP endpoint can do
 * any of this, so a stolen agent key cannot mint keys. Secrets are printed once to stdout and are
 * never logged or stored; status messages go to stderr.
 *
 * <pre>
 *   docker exec -it lifeos-api lifeos-admin bootstrap
 *   docker exec -it lifeos-api lifeos-admin mint-key grokbot-chat --rotate
 * </pre>
 */
public final class LifeAdmin {

    public static final String CHAT_CLIENT = "grokbot-chat";
    public static final String ROUTINE_CLIENT = "grokbot-routine";
    static final Duration OTT_TTL = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final ApiKeyService keys;
    private final SecurityEventLog events;
    private final PrintStream out;
    private final PrintStream err;
    private final String baseUrl;
    private final Clock clock;

    LifeAdmin(JdbcTemplate jdbc, String pepper, String baseUrl, PrintStream out, PrintStream err, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.events = new SecurityEventLog(jdbc, clock);
        this.keys = new ApiKeyService(new ApiKeyStore(jdbc), new KeyHasher(pepper), events, clock);
        this.out = out;
        this.err = err;
        this.baseUrl = baseUrl;
    }

    public static void main(String[] args) {
        quietLogging();
        int code;
        try {
            code = run(args, System.getenv(), System.out, System.err);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("error: " + e.getMessage());
            code = 2;
        }
        System.exit(code);
    }

    /** No Spring here: keep logback off stdout (stdout carries secrets, e.g. `--format env > file`). */
    static void quietLogging() {
        if (!(org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext ctx)) return;
        ctx.reset();
        var appender = new ch.qos.logback.core.ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.setContext(ctx);
        appender.setTarget("System.err");
        var enc = new ch.qos.logback.classic.encoder.PatternLayoutEncoder();
        enc.setContext(ctx);
        enc.setPattern("%level %logger{20}: %msg%n");
        enc.start();
        appender.setEncoder(enc);
        appender.start();
        var root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(ch.qos.logback.classic.Level.WARN);
        root.addAppender(appender);
    }

    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            err.println(USAGE);
            return args.length == 0 ? 2 : 0;
        }
        boolean local = env.getOrDefault("SPRING_PROFILES_ACTIVE", "").contains("local");
        String pepper = env.get("LIFE_KEY_PEPPER");
        if ((pepper == null || pepper.isBlank()) && local) pepper = AuthProperties.LOCAL_DEV_PEPPER;
        if (pepper == null || pepper.length() < KeyHasher.MIN_PEPPER_LENGTH) {
            throw new IllegalStateException("LIFE_KEY_PEPPER is not set (or < 32 chars): use the same value as the app");
        }
        LifeAdmin admin = new LifeAdmin(new JdbcTemplate(dataSource(env, local)), pepper, baseUrl(env), out, err,
                Clock.systemUTC());
        return admin.dispatch(args);
    }

    static SimpleDriverDataSource dataSource(Map<String, String> env, boolean local) {
        String url = env.get("TURSO_DATABASE_URL");
        if (local || url == null || url.isBlank()) {
            String db = env.get("LIFE_DB");
            if (db == null || db.isBlank()) {
                throw new IllegalStateException("no database: set TURSO_DATABASE_URL (+ TURSO_AUTH_TOKEN), or LIFE_DB with the local profile");
            }
            return new SimpleDriverDataSource(new org.sqlite.JDBC(), "jdbc:sqlite:" + db);
        }
        return new SimpleDriverDataSource(new LibSqlHttpDriver(), url, "", env.getOrDefault("TURSO_AUTH_TOKEN", ""));
    }

    static String baseUrl(Map<String, String> env) {
        return new AuthProperties(null, false, 0, env.get("LIFE_DOMAIN"), env.get("LIFE_PUBLIC_URL")).baseUrl();
    }

    int dispatch(String[] args) {
        Opts o = Opts.parse(Arrays.copyOfRange(args, 1, args.length));
        requireSchema();
        switch (args[0]) {
            case "bootstrap" -> bootstrap(o);
            case "create-client" -> createClient(o);
            case "mint-key" -> printKey(keys.mint(o.positional(0, "client id"), o.scopes(), o.ttlDays(), o.flag("rotate")), o);
            case "revoke-key" -> {
                String keyId = o.positional(0, "key id");
                if (!keys.revoke(keyId, o.value("reason", "manual"))) throw new IllegalArgumentException("no active key " + keyId);
                err.println("revoked " + keyId + " (effective on its next request)");
            }
            case "list" -> list();
            case "owner-login" -> ownerLogin(o.flag("recovery"));
            default -> throw new IllegalArgumentException("unknown command " + args[0] + "\n" + USAGE);
        }
        return 0;
    }

    /** Idempotent first-time setup: Grok Bot's two clients + keys, and the owner's first-passkey link. */
    void bootstrap(Opts o) {
        record Spec(String id, String name, List<String> scopes) {}
        for (Spec s : List.of(new Spec(CHAT_CLIENT, "Grok Bot (chat)", Scopes.GROKBOT_CHAT),
                new Spec(ROUTINE_CLIENT, "Grok Bot (routine wake check)", Scopes.GROKBOT_ROUTINE))) {
            ApiKeyStore.ClientRecord c = keys.ensureClient(s.id(), s.name(), ClientKind.AGENT, s.scopes());
            long active = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM api_keys WHERE client_id = ? AND revoked_at IS NULL
                      AND (expires_at IS NULL OR expires_at > ?)""", Long.class, c.id(), now());
            if (active > 0) {
                err.println(s.id() + ": already has " + active + " active key(s); not minting (use: mint-key " + s.id() + " --rotate)");
            } else {
                printKey(keys.mint(s.id(), null, o.ttlDays(), false), o);
            }
        }
        // Deliberately NOT minting the owner's login link here: this output is meant for Grok Bot's
        // secret store, and an agent holding the owner link could register its own passkey.
        err.println(passkeyCount() == 0
                ? "owner: no passkey yet. Run `lifeos-admin owner-login` YOURSELF (not Grok Bot) and open the link."
                : "owner: passkey registered.");
    }

    void createClient(Opts o) {
        String id = o.positional(0, "client id");
        ClientKind kind = switch (o.value("kind", "agent")) {
            case "agent" -> ClientKind.AGENT;
            case "service" -> ClientKind.SERVICE;
            case "human_app" -> ClientKind.HUMAN_APP;
            default -> throw new IllegalArgumentException("--kind must be agent, service or human_app");
        };
        List<String> scopes = o.scopes();
        if (scopes == null) throw new IllegalArgumentException("--scopes \"...\" is required");
        keys.ensureClient(id, o.value("name", id), kind, scopes);
        err.println("client " + id + " (" + kind.name().toLowerCase(Locale.ROOT) + "): max scopes " + Scopes.format(scopes)
                + "\nnext: lifeos-admin mint-key " + id);
    }

    void list() {
        out.println("clients:");
        for (Map<String, Object> c : keys.listClients()) out.println("  " + c);
        out.println("keys (no secrets are stored):");
        for (Map<String, Object> k : keys.listKeys()) out.println("  " + k);
        out.println("passkeys registered: " + passkeyCount());
    }

    /** A one-time login link for the owner (5 minutes, single use) to register a passkey. */
    void ownerLogin(boolean recovery) {
        if (passkeyCount() > 0 && !recovery) {
            throw new IllegalStateException("a passkey is already registered; use --recovery only if you lost it");
        }
        String token = UUID.randomUUID().toString();
        Instant expires = Instant.now(clock).plus(OTT_TTL);
        jdbc.update("DELETE FROM one_time_tokens WHERE username = ?", OwnerAccess.OWNER_USERNAME);
        jdbc.update("INSERT INTO one_time_tokens (token_value, username, expires_at) VALUES (?,?,?)",
                token, OwnerAccess.OWNER_USERNAME, Timestamp.from(expires));
        events.record(SecurityEventLog.Event.of("owner_login_token_created").credential("ott")
                .outcome(recovery ? "recovery" : "bootstrap"));
        err.println("owner: open this link within 5 minutes (single use), then register a passkey:");
        out.println(baseUrl + "/login/ott?token=" + token);
    }

    private void printKey(ApiKeyService.MintedKey k, Opts o) {
        if ("env".equals(o.value("format", "text"))) {
            out.println("LIFEOS_" + k.clientId().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_") + "_KEY=" + k.key());
        } else {
            err.println(k.clientId() + ": key " + k.keyId() + ", scopes " + Scopes.format(k.scopes())
                    + ", expires " + (k.expiresAt() == null ? "never" : k.expiresAt()) + ". Shown once:");
            out.println(k.key());
        }
    }

    private long passkeyCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM user_credentials", Long.class);
        return n == null ? 0 : n;
    }

    private void requireSchema() {
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM api_clients", Long.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("auth tables missing: start the app once (it applies migration 0005) and retry");
        }
    }

    private String now() {
        return Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    /** --flag, --key value, positionals. */
    record Opts(List<String> positionals, Map<String, String> values) {
        static Opts parse(String[] a) {
            List<String> pos = new ArrayList<>();
            Map<String, String> vals = new java.util.HashMap<>();
            for (int i = 0; i < a.length; i++) {
                if (a[i].startsWith("--")) {
                    String k = a[i].substring(2);
                    int eq = k.indexOf('=');
                    if (eq > 0) vals.put(k.substring(0, eq), k.substring(eq + 1));
                    else if (i + 1 < a.length && !a[i + 1].startsWith("--") && !k.equals("rotate") && !k.equals("recovery"))
                        vals.put(k, a[++i]);
                    else vals.put(k, "true");
                } else pos.add(a[i]);
            }
            return new Opts(pos, vals);
        }

        String positional(int i, String what) {
            if (positionals.size() <= i) throw new IllegalArgumentException("missing " + what);
            return positionals.get(i);
        }

        String value(String k, String def) {
            return values.getOrDefault(k, def);
        }

        boolean flag(String k) {
            return "true".equals(values.get(k));
        }

        List<String> scopes() {
            String s = values.get("scopes");
            return s == null ? null : List.copyOf(Scopes.parse(s));
        }

        int ttlDays() {
            return Integer.parseInt(value("ttl-days", String.valueOf(ApiKeyService.DEFAULT_TTL_DAYS)));
        }
    }

    static final String USAGE = """
            lifeos-admin <command>   (run on the server: docker exec -it lifeos-api lifeos-admin ...)
              bootstrap [--format env] [--ttl-days 90]
                  Grok Bot's clients + keys (grokbot-chat: finance/fridge/memos/stocks write + ops read/write;
                  grokbot-routine: memos:read ops:read). Idempotent: never re-mints for a client that has an
                  active key. Prints only agent keys (safe to hand to Grok Bot's secret store).
              mint-key <client> [--scopes "a b"] [--ttl-days N (0 = never)] [--rotate] [--format env]
                  New key (max 2 active per client; --rotate revokes the oldest).
              revoke-key <key_id> [--reason text]
              create-client <client> --kind agent|service|human_app --scopes "..." [--name "..."]
                  (ops:admin is refused for agents)
              list                      clients, keys (ids, scopes, last use), passkey count
              owner-login [--recovery]  one-time (5 min) link to register YOUR passkey. Run it yourself;
                                        never give this link to an agent.
            Secrets go to stdout only, once. Save them straight into a secret store, e.g.
              ( umask 077; docker exec lifeos-api lifeos-admin bootstrap --format env > ~/grokbot-keys.env )
            """;
}
