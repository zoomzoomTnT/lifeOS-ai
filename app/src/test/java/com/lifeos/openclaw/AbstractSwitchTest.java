package com.lifeos.openclaw;

import com.lifeos.receipts.TestDb;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

abstract class AbstractSwitchTest {

    static Path newDb() {
        try {
            return TestDb.create();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext ctx;

    @Autowired
    List<ScheduledTaskHolder> schedulers;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** "Class.method" of every registered @Scheduled task. */
    String scheduledTasks() {
        StringBuilder sb = new StringBuilder();
        schedulers.forEach(h -> h.getScheduledTasks().forEach(t -> sb.append(t.getTask()).append('\n')));
        return sb.toString();
    }
}
