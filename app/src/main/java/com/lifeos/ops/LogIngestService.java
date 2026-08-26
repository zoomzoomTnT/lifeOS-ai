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
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogIngestService {

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
        int app = 0, session = 0, files = 0;
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
            session += ingestFile(f, true);
        }
        for (Path f : gatewayFiles) {
            files++;
            app += ingestFile(f, false);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("files", files);
        out.put("session_rows", session);
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
                  SELECT id, occurred_at, ingested_at, source, agent_id, session_id, event_type, role,
                         content, content_len, usage_json, file_path, line_no, raw_json
                  FROM ai_session_logs ORDER BY id DESC LIMIT ?
                  """
                : """
                  SELECT id, occurred_at, ingested_at, source, agent_id, session_id, event_type, role,
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
                """, source, level, loggerName, cap(message));
    }

    private int ingestFile(Path file, boolean sessionFile) {
        String path = file.toAbsolutePath().normalize().toString();
        long startOff = 0;
        int startLine = 0;
        List<Map<String, Object>> cur = jdbc.queryForList(
                "SELECT offset_bytes, line_no FROM log_ingest_cursors WHERE file_path = ?", path);
        if (!cur.isEmpty()) {
            startOff = ((Number) cur.get(0).get("offset_bytes")).longValue();
            startLine = ((Number) cur.get(0).get("line_no")).intValue();
        }
        int inserted = 0;
        try {
            long size = Files.size(file);
            if (startOff > size) {
                startOff = 0;
                startLine = 0;
            }
            long consumed = startOff;
            int lineNo = startLine;
            try (InputStream raw = Files.newInputStream(file)) {
                if (startOff > 0) raw.skipNBytes(startOff);
                BufferedReader reader = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    lineNo++;
                    consumed += line.getBytes(StandardCharsets.UTF_8).length + 1L;
                    if (line.isBlank()) continue;
                    if (sessionFile) {
                        if (insertSession(path, lineNo, line)) inserted++;
                    } else if (insertGateway(line)) {
                        inserted++;
                    }
                }
            }
            jdbc.update("""
                    INSERT INTO log_ingest_cursors (file_path, offset_bytes, line_no, updated_at)
                    VALUES (?,?,?, strftime('%Y-%m-%dT%H:%M:%SZ','now'))
                    ON CONFLICT(file_path) DO UPDATE SET
                      offset_bytes=excluded.offset_bytes,
                      line_no=excluded.line_no,
                      updated_at=excluded.updated_at
                    """, path, consumed, lineNo);
        } catch (Exception e) {
            log.warn("ingest {} failed: {}", file, e.getMessage());
        }
        return inserted;
    }

    private boolean insertSession(String filePath, int lineNo, String line) {
        JsonlParser.Parsed p = parser.parse(line);
        if (p == null) return false;
        String occurred = p.occurredAt() != null ? p.occurredAt() : nowUtc();
        String raw = cap(line);
        try {
            int n = jdbc.update("""
                    INSERT OR IGNORE INTO ai_session_logs (
                      occurred_at, source, agent_id, session_id, session_key,
                      event_type, role, content, content_len, usage_json,
                      file_path, line_no, raw_json)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    occurred, sourceFor(filePath), p.agentId(), p.sessionId(), p.sessionKey(),
                    p.eventType(), p.role(), p.content(),
                    p.content() == null ? 0 : p.content().length(),
                    p.usageJson(), filePath, lineNo, raw);
            return n > 0;
        } catch (Exception e) {
            log.debug("session row skip: {}", e.getMessage());
            return false;
        }
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
                """, occurred, "openclaw_gateway", level, "openclaw", cap(msg));
        return true;
    }

    private Path resolveHome() {
        if (openclawHome != null && !openclawHome.isBlank()) return Path.of(openclawHome);
        String h = System.getProperty("user.home");
        return h == null ? null : Path.of(h, ".openclaw");
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

    private static String cap(String s) {
        if (s == null) return null;
        return s.length() > JsonlParser.CONTENT_CAP ? s.substring(0, JsonlParser.CONTENT_CAP) + "…" : s;
    }
}
