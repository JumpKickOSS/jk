// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.config.TestQuarantine;
import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.runtime.TestFlakes;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/project/flaky?dir=<checkout>}: the project page's Flaky tests tab. Each class of
 * the checkout's modules that passed on a re-run in the quarantine window, and each class {@value
 * TestQuarantine#FILE} holds, with its recent outcomes.
 */
final class HttpFlakyApi {

    /** The route. */
    static final String PATH = "/api/project/flaky";

    private HttpFlakyApi() {}

    static void handle(HttpExchange exchange) throws IOException {
        String dir = HttpQuery.queryParam(exchange.getRequestURI().getRawQuery(), "dir");
        if (dir == null || dir.isBlank()) {
            HttpResponses.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"dir\"").toString());
            return;
        }
        Path root = Path.of(dir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            HttpResponses.sendJson(
                    exchange,
                    404,
                    JsonOut.object().put("error", "no such directory: " + root).toString());
            return;
        }
        if (HttpResponses.answeredHead(exchange)) return;
        HttpResponses.sendJson(exchange, 200, MiniJson.write(body(root, TestFlakes.report(JkDirs.state(), root))));
    }

    /**
     * {@code dir}, {@code policy} ({@code flakes} and {@code runs}, {@code flakes} 0 when jk
     * quarantines nothing on its own), {@code file}, {@code error} when the file is unreadable, and
     * {@code classes}: {@code name}, {@code module}, {@code history} (oldest first: {@code .} passed,
     * {@code R} passed on a re-run, {@code F} failed), {@code flakes}, and {@code quarantined} with
     * the entry's {@code reason}, {@code since} and {@code issue} ({@code id}, {@code url}).
     */
    static Map<String, Object> body(Path root, TestFlakes.Report report) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("flakes", report.policy().off() ? 0 : report.policy().flakes());
        policy.put("runs", report.policy().runs());
        List<Map<String, Object>> classes = new ArrayList<>();
        for (TestFlakes.Row r : report.rows()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", r.className());
            row.put("module", r.module());
            row.put("history", r.history());
            row.put("flakes", r.flakes());
            TestQuarantine.Entry e = r.held();
            if (e != null) {
                Map<String, Object> held = new LinkedHashMap<>();
                held.put("reason", e.reason());
                held.put("since", e.since());
                TestQuarantine.Issue issue = e.issue();
                if (issue != null) held.put("issue", issue.fields());
                row.put("quarantined", held);
            }
            classes.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dir", root.toString());
        body.put("policy", policy);
        body.put("file", TestQuarantine.FILE);
        if (report.error() != null) body.put("error", report.error());
        body.put("classes", classes);
        return body;
    }
}
