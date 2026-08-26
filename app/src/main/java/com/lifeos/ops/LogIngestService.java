package com.lifeos.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogIngestService {

    private static final Pattern AGENT_IN_PATH = Pattern.compile("/agents/([^/]+)/");

    private final JdbcTemplate jdbc;
    private final JsonlParser parser;
    private final ObjectMapper mapper;

    @Value("${life.openclaw.home:}")
    private String openclawHome;

    @Value("${life.openclaw.file-log:}")
    private String fileLogDir;

    @Scheduled(fixedDelayString = "${life.logs.ingest-ms:120000}")
    public void tick() {
        run();
    }

    public Map<String, Object> run() {
        int app = 0, session = 0, files = 0, aiCalls = 0;
        Path home = resolveHome();
        List<Path> sessionFiles = new ArrayList<>();
        if (home != null && Files.isDirectory(home)) {
            sessionFiles.addAll(walk(home.resolve("agents"), 6, ".jsonl"));
            sessionFiles.addAll(walk(home.resolve("transcripts"), 5, "transcript.jsonl"));
            sessionFiles.addAll(walk(home.resolve("workspace"), 8, "events.jsonl"));
            sessionFiles.addAll(walk(home.resolve("trajectory-exports"), 6, "events.jsonl"));
        }
        List<Path> gatewayFiles = List.of();
        if (fileLogDir != null && !fileLogDir.isBlank()) {
            gatewayFiles = walk(Path.of(fileLogDir), 2, ".log");
        }
        for (Path f : sessionFiles) {
            files++;
            int[] n = ingestFile(f, true);
            session += n[0];
            aiCalls += n[1];
        }
        for (Path f : gatewayFiles) {
            files++;
            app += ingestFile(f, false)[0];
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("files", files);
        out.put("session_rows", session);
        out.put("ai_call_rows", aiCalls);
        out.put("app_rows", app);
        out.put("home", home == null ? "" : home.toString());
        return out;
    }

    public List<Map<String, Object>> listApp(int limit) {
        return jdbc.queryForList("""
                SELECT id, occurred_at, ingested_at, source, level, logger, message
                FROM app_logs ORDER BY id DESC LIMIT ?
                """, Math.min(Math.max(limit, 1), 200));
    }

    public List<Map<String, Object>> listSessions(int limit, boolean includeContent) {
        String sql = includeContent
                ? """
                  SELECT id, occurred_at, ingested_at, source, agent_id, session_id, event_id, parent_id,
                         event_type, role, provider, model, stop_reason, tool_name, heartbeat,
                         prompt_tokens, completion_tokens, cache_read_tokens, cache_write_tokens,
                         total_tokens, cost_micros, media_paths_json, custom_type,
                         content, content_len, usage_json, file_path, line_no, raw_json
                  FROM ai_session_logs ORDER BY id DESC LIMIT ?
                  """
                : """
                  SELECT id, occurred_at, ingested_at, source, agent_id, session_id, event_id, parent_id,
                         event_type, role, provider, model, stop_reason, tool_name, heartbeat,
                         prompt_tokens, completion_tokens, cache_read_tokens, cache_write_tokens,
                         total_tokens, cost_micros, media_paths_json, custom_type,
                         content_len, file_path, line_no
                  FROM ai_session_logs ORDER BY id DESC LIMIT ?
                  """;
        return jdbc.queryForList(sql, Math.min(Math.max(limit, 1), 200));
    }

    public Map<String, Object> counts() {
        Long app = jdbc.queryForObject("SELECT COUNT(*) FROM app_logs", Long.class);
        Long sess = jdbc.queryForObject("SELECT COUNT(*) FROM ai_session_logs", Long.class);
        return Map.of(
                "app_logs", app == null ? 0 : app,
                "ai_session_logs", sess == null ? 0 : sess);
    }

    public void app(String source, String level, String loggerName, String message) {
        jdbc.update("""
                INSERT INTO app_logs (occurred_at, source, level, logger, message)
                VALUES (strftime('%Y-%m-%dT%H:%M:%SZ','now'), ?, ?, ?, ?)
                """, source, level, loggerName, JsonlParser.cap(message));
    }

    /** @return [sessionOrAppRows, aiCallRows] */
    private int[] ingestFile(Path file, boolean sessionFile) {
        String path = file.toAbsolutePath().normalize().toString();
        long startOff = 0;
        int startLine = 0;
        String lastSessionId = null;
        int inserted = 0;
        int aiCalls = 0;
        try {
            List<Map<String, Object>> cur = jdbc.queryForList(
                    "SELECT offset_bytes, line_no, last_session_id FROM log_ingest_cursors WHERE file_path = ?", path);
            if (!cur.isEmpty()) {
                startOff = ((Number) cur.get(0).get("offset_bytes")).longValue();
                startLine = ((Number) cur.get(0).get("line_no")).intValue();
                Object sid = cur.get(0).get("last_session_id");
                if (sid != null) lastSessionId = sid.toString();
            }
            long size = Files.size(file);
            if (startOff > size) {
                startOff = 0;
                startLine = 0;
            }
            long consumed = startOff;
            int lineNo = startLine;
            String agentFromPath = agentFromPath(path);
            try (InputStream raw = Files.newInputStream(file)) {
                if (startOff > 0) raw.skipNBytes(startOff);
                BufferedReader reader = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    lineNo++;
                    consumed += line.getBytes(StandardCharsets.UTF_8).length + 1L;
                    if (line.isBlank()) continue;
                    if (sessionFile) {
                        JsonlParser.Parsed p = parser.parse(line);
                        if (p == null) continue;
                        if (p.sessionId() != null) lastSessionId = p.sessionId();
                        if (insertSession(path, lineNo, p, lastSessionId, agentFromPath)) {
                            inserted++;
                            if (recordUsage(p, lastSessionId)) aiCalls++;
                        }
                    } else if (insertGateway(line)) {
                        inserted++;
                    }
                }
            }
            jdbc.update("""
                    INSERT INTO log_ingest_cursors (file_path, offset_bytes, line_no, last_session_id, updated_at)
                    VALUES (?,?,?,?, strftime('%Y-%m-%dT%H:%M:%SZ','now'))
                    ON CONFLICT(file_path) DO UPDATE SET
                      offset_bytes=excluded.offset_bytes,
                      line_no=excluded.line_no,
                      last_session_id=excluded.last_session_id,
                      updated_at=excluded.updated_at
                    """, path, consumed, lineNo, lastSessionId);
        } catch (Exception e) {
            log.warn("ingest {} failed: {}", file, e.getMessage());
        }
        return new int[] {inserted, aiCalls};
    }

    private boolean insertSession(String filePath, int lineNo, JsonlParser.Parsed p,
                                  String sessionId, String agentFromPath) {
        String occurred = p.occurredAt() != null ? p.occurredAt() : nowUtc();
        String agent = p.agentId() != null ? p.agentId() : agentFromPath;
        try {
            int n = jdbc.update("""
                    INSERT OR IGNORE INTO ai_session_logs (
                      occurred_at, source, agent_id, session_id, session_key,
                      event_id, parent_id, event_type, role, provider, model,
                      stop_reason, tool_name, custom_type, heartbeat,
                      prompt_tokens, completion_tokens, cache_read_tokens, cache_write_tokens,
                      total_tokens, cost_micros, media_paths_json,
                      content, content_len, usage_json, file_path, line_no, raw_json)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    occurred, sourceFor(filePath), agent, sessionId, p.sessionKey(),
                    p.eventId(), p.parentId(), p.eventType(), p.role(), p.provider(), p.model(),
                    p.stopReason(), p.toolName(), p.customType(), p.heartbeat() ? 1 : 0,
                    p.promptTokens(), p.completionTokens(), p.cacheReadTokens(), p.cacheWriteTokens(),
                    p.totalTokens(), p.costMicros(), p.mediaPathsJson(),
                    p.content(), p.content() == null ? 0 : p.content().length(),
                    p.usageJson(), filePath, lineNo, p.sanitizedRawJson());
            return n > 0;
        } catch (Exception e) {
            log.debug("session row skip: {}", e.getMessage());
            return false;
        }
    }

    private boolean recordUsage(JsonlParser.Parsed p, String sessionId) {
        if (p.promptTokens() == null && p.completionTokens() == null && p.totalTokens() == null) {
            return false;
        }
        if (p.eventId() == null) return false;
        String corr = "oc:" + p.eventId();
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_calls WHERE correlation_id = ?", Integer.class, corr);
        if (exists != null && exists > 0) return false;
        long prompt = p.promptTokens() == null ? 0 : p.promptTokens();
        long completion = p.completionTokens() == null ? 0 : p.completionTokens();
        long total = p.totalTokens() != null ? p.totalTokens() : prompt + completion;
        String provider = p.provider() == null ? "unknown" : p.provider();
        String model = p.model() == null ? "unknown" : p.model();
        long cost = p.costMicros() == null ? 0 : p.costMicros();
        if (cost == 0 && !"unknown".equals(provider)) {
            List<Map<String, Object>> prices = jdbc.queryForList(
                    "SELECT input_usd_micros_per_mtok, output_usd_micros_per_mtok FROM model_prices WHERE provider=? AND model=?",
                    provider, model);
            if (!prices.isEmpty()) {
                cost = CostCalculator.costMicros(
                        prompt, completion,
                        ((Number) prices.get(0).get("input_usd_micros_per_mtok")).longValue(),
                        ((Number) prices.get(0).get("output_usd_micros_per_mtok")).longValue());
            }
        }
        String purpose = p.heartbeat() ? "heartbeat" : "chat";
        try {
            jdbc.update("""
                    INSERT INTO ai_calls (
                      correlation_id, source, purpose, provider, model,
                      prompt_tokens, completion_tokens, total_tokens, cost_micros, currency,
                      status, meta_json)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    corr, "other", purpose, provider, model,
                    prompt, completion, total, cost, "USD", "ok",
                    "{\"session_id\":" + quote(sessionId)
                            + ",\"event_id\":" + quote(p.eventId())
                            + ",\"cache_read\":" + p.cacheReadTokens()
                            + ",\"cache_write\":" + p.cacheWriteTokens() + "}");
            return true;
        } catch (Exception e) {
            log.debug("ai_calls from session skip: {}", e.getMessage());
            return false;
        }
    }

    private static String quote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private boolean insertGateway(String line) {
        JsonlParser.Parsed p = parser.parse(line);
        String occurred = p != null && p.occurredAt() != null ? p.occurredAt() : nowUtc();
        String msg = p != null && p.content() != null ? p.content() : line;
        String level = "INFO";
        try {
            var n = mapper.readTree(line);
            if (n.has("level")) level = n.get("level").asText("INFO").toUpperCase();
        } catch (Exception ignored) { }
        jdbc.update("""
                INSERT INTO app_logs (occurred_at, source, level, logger, message)
                VALUES (?,?,?,?,?)
                """, occurred, "openclaw_gateway", level, "openclaw", JsonlParser.cap(msg));
        return true;
    }

    private Path resolveHome() {
        if (openclawHome != null && !openclawHome.isBlank()) return Path.of(openclawHome);
        String h = System.getProperty("user.home");
        return h == null ? null : Path.of(h, ".openclaw");
    }

    private static String agentFromPath(String path) {
        Matcher m = AGENT_IN_PATH.matcher(path.replace('\\', '/'));
        return m.find() ? m.group(1) : null;
    }

    private static List<Path> walk(Path root, int maxDepth, String suffix) {
        if (root == null || !Files.isDirectory(root)) return List.of();
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root, maxDepth)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                if ("sessions.json".equals(name)) return;
                if (name.endsWith(suffix)) out.add(p);
            });
        } catch (Exception e) {
            log.debug("walk {}: {}", root, e.getMessage());
        }
        return out;
    }

    private static String sourceFor(String path) {
        String p = path.replace('\\', '/').toLowerCase();
        if (p.contains("trajectory") || p.endsWith("/events.jsonl")) return "trajectory";
        if (p.contains("/transcripts/") || p.endsWith("transcript.jsonl")) return "transcript";
        return "openclaw_session";
    }

    private static String nowUtc() {
        return java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }
}
