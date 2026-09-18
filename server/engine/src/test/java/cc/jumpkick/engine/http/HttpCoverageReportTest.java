// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The run's coverage HTML is reachable from the dashboard: the tokenized entry link redirects to
 * the report's page, the report's own relative links resolve under the same route, anything
 * outside the report directories is refused, and a bad token is a 401.
 */
@Tag("integration")
class HttpCoverageReportTest extends HttpEngineServerHarness {

    @Test
    void a_run_with_coverage_serves_its_report_tree_behind_the_path_token() throws Exception {
        Path ws = Files.createDirectories(stateDir.resolve("ws"));
        Path report = Files.createDirectories(ws.resolve("lib/target/reports/coverage"));
        Files.writeString(report.resolve("index.html"), "<html><link href=\"jacoco-resources/report.css\"></html>");
        Files.createDirectories(report.resolve("jacoco-resources"));
        Files.writeString(report.resolve("jacoco-resources/report.css"), "body{}");
        Files.writeString(ws.resolve("lib/target/secret.txt"), "not a report");
        String id = finished(testJournal(), ws, List.of(module(ws, "lib", 120, 30)));

        HttpResponse<String> entry = fetch("/report/" + token() + "/" + id + "/");
        assertThat(entry.statusCode()).isEqualTo(302);
        assertThat(entry.headers().firstValue("Location"))
                .contains("/report/" + token() + "/" + id + "/lib/target/reports/coverage/index.html");

        HttpResponse<String> page = fetch("/report/" + token() + "/" + id + "/lib/target/reports/coverage/index.html");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type")).contains("text/html; charset=utf-8");
        assertThat(page.headers().firstValue("Content-Security-Policy")).contains("sandbox");
        assertThat(page.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(page.body()).contains("jacoco-resources/report.css");

        HttpResponse<String> css =
                fetch("/report/" + token() + "/" + id + "/lib/target/reports/coverage/jacoco-resources/report.css");
        assertThat(css.statusCode()).isEqualTo(200);
        assertThat(css.headers().firstValue("Content-Type")).contains("text/css; charset=utf-8");

        assertThat(fetch("/report/" + token() + "/" + id + "/lib/target/secret.txt")
                        .statusCode())
                .isEqualTo(404);
        assertThat(fetch("/report/" + token() + "/" + id + "/lib/target/reports/coverage/../secret.txt")
                        .statusCode())
                .isEqualTo(404);
        assertThat(fetch("/report/nope/" + id + "/").statusCode()).isEqualTo(401);
        assertThat(fetch("/report/" + token() + "/no-such-run/").statusCode()).isEqualTo(404);
    }

    @Test
    void a_workspace_run_enters_at_the_roll_up_and_serves_every_module_page() throws Exception {
        Path ws = Files.createDirectories(stateDir.resolve("ws2"));
        for (String m : List.of("core", "app")) {
            Path dir = Files.createDirectories(ws.resolve(m + "/target/reports/coverage"));
            Files.writeString(dir.resolve("index.html"), "<html>" + m + "</html>");
        }
        Path rollup = Files.createDirectories(ws.resolve("target/reports/coverage"));
        Files.writeString(rollup.resolve("index.html"), "<html>roll-up</html>");
        String id = finished(testJournal(), ws, List.of(module(ws, "core", 10, 10), module(ws, "app", 5, 5)));

        HttpResponse<String> entry = fetch("/report/" + token() + "/" + id + "/");
        assertThat(entry.headers().firstValue("Location"))
                .contains("/report/" + token() + "/" + id + "/target/reports/coverage/index.html");
        assertThat(fetch("/report/" + token() + "/" + id + "/target/reports/coverage/index.html")
                        .body())
                .contains("roll-up");
        assertThat(fetch("/report/" + token() + "/" + id + "/app/target/reports/coverage/index.html")
                        .body())
                .contains("app");
    }

    /** No bearer header and no redirect following: the browser's own navigation, seen one hop at a time. */
    private HttpResponse<String> fetch(String path) throws Exception {
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(baseUrl + path.substring(1))).build();
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static BuildRecord.Coverage module(Path ws, String name, long covered, long missed) {
        return new BuildRecord.Coverage(
                ws.resolve(name).toString(),
                "g:" + name,
                covered,
                missed,
                covered / 2,
                missed / 2,
                ws.resolve(name + "/target/reports/coverage/index.html").toString());
    }

    /** One finished coverage run of {@code ws} in the journal; returns a locator the route accepts. */
    private String finished(BuildJournal journal, Path ws, List<BuildRecord.Coverage> coverage) throws Exception {
        Files.writeString(ws.resolve("jk.toml"), "group = \"g\"\nname = \"ws\"\nversion = \"1\"\n");
        String dir = ws.toString();
        long t = System.currentTimeMillis();
        String locator = requireNonNull(
                journal.begin(BuildRecord.running(7, "test", dir, "g:ws", "p7", t, "9.9", "cli", null, 7)));
        BuildRecord done = new BuildRecord(
                null,
                7,
                BuildRecord.SCHEMA,
                "test",
                dir,
                "g:ws",
                "p7",
                t,
                t + 500,
                500,
                true,
                false,
                0,
                "9.9",
                null,
                List.of(),
                List.of(),
                List.of(),
                "cli",
                null,
                null,
                null,
                false,
                null,
                7,
                null,
                coverage,
                null);
        assertThat(journal.complete(locator, done, BuildJournal.Snapshot.NONE)).isTrue();
        return locator; // the job-directory locator names the run as a record id does
    }
}
