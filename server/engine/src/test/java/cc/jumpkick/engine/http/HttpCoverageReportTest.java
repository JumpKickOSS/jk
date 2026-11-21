// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.testing.Symlinks;
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
 * The run's coverage HTML is reachable from the dashboard: the entry link, carrying the run's report
 * token, redirects to the report's page, the report's own relative links resolve under the same
 * route, anything outside the report directories is refused (by real path, so a planted symlink
 * does not leak its target), an oversized file is a 413, and a bad token, the bearer token
 * included, is a 403.
 */
@Tag("integration")
class HttpCoverageReportTest extends HttpEngineServerHarness {

    @Test
    void a_run_with_coverage_serves_its_report_tree_behind_the_path_token() throws Exception {
        Path ws = Files.createDirectories(stateDir.resolve("ws"));
        Path report = Files.createDirectories(ws.resolve("lib/target/site/jacoco"));
        Files.writeString(report.resolve("index.html"), "<html><link href=\"jacoco-resources/report.css\"></html>");
        Files.createDirectories(report.resolve("jacoco-resources"));
        Files.writeString(report.resolve("jacoco-resources/report.css"), "body{}");
        Files.writeString(ws.resolve("lib/target/secret.txt"), "not a report");
        String id = finished(testJournal(), ws, List.of(module(ws, "lib", 120, 30)));
        String rt = reportToken(id);

        HttpResponse<String> entry = fetch("/report/" + rt + "/" + id + "/");
        assertThat(entry.statusCode()).isEqualTo(302);
        assertThat(entry.headers().firstValue("Location"))
                .as("relative, so the token never rides in a header")
                .contains("./lib/target/site/jacoco/index.html");
        HttpResponse<String> bare = fetch("/report/" + rt + "/" + id);
        assertThat(bare.statusCode()).isEqualTo(302);
        assertThat(landing("/report/" + rt + "/" + id, bare))
                .isEqualTo("/report/" + rt + "/" + id + "/lib/target/site/jacoco/index.html");
        assertThat(bare.headers().map().toString()).doesNotContain(rt);

        HttpResponse<String> page = fetch("/report/" + rt + "/" + id + "/lib/target/site/jacoco/index.html");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type")).contains("text/html; charset=utf-8");
        assertThat(page.headers().firstValue("Content-Security-Policy")).contains("sandbox");
        assertThat(page.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(page.body()).contains("jacoco-resources/report.css");

        HttpResponse<String> css =
                fetch("/report/" + rt + "/" + id + "/lib/target/site/jacoco/jacoco-resources/report.css");
        assertThat(css.statusCode()).isEqualTo(200);
        assertThat(css.headers().firstValue("Content-Type")).contains("text/css; charset=utf-8");

        assertThat(fetch("/report/" + rt + "/" + id + "/lib/target/secret.txt").statusCode())
                .isEqualTo(404);
        assertThat(fetch("/report/" + rt + "/" + id + "/lib/target/site/jacoco/../secret.txt")
                        .statusCode())
                .isEqualTo(404);
        assertThat(fetch("/report/nope/" + id + "/").statusCode()).isEqualTo(403);
        assertThat(fetch("/report/" + rt + "/no-such-run/").statusCode())
                .as("a report token opens its own run alone")
                .isEqualTo(403);
    }

    @Test
    void the_bearer_token_opens_no_report_and_a_report_token_is_handed_out_only_for_a_run_with_one() throws Exception {
        Path ws = Files.createDirectories(stateDir.resolve("ws5"));
        Path report = Files.createDirectories(ws.resolve("lib/target/site/jacoco"));
        Files.writeString(report.resolve("index.html"), "<html>lib</html>");
        String id = finished(testJournal(), ws, List.of(module(ws, "lib", 1, 1)));

        assertThat(fetch("/report/" + token() + "/" + id + "/lib/target/site/jacoco/index.html")
                        .statusCode())
                .as("a URL in the browser's history is no credential for the engine")
                .isEqualTo(403);
        HttpResponse<String> minted = get(HttpCoverageReport.TOKEN + "?id=" + id);
        assertThat(minted.statusCode()).isEqualTo(200);
        assertThat(minted.body()).contains("\"expiresAt\":").doesNotContain(token());
        assertThat(get(HttpCoverageReport.TOKEN + "?id=" + id, "Authorization", "Bearer wrong")
                        .statusCode())
                .isEqualTo(401);
        assertThat(get(HttpCoverageReport.TOKEN + "?id=no-such-run").statusCode())
                .isEqualTo(404);
        assertThat(get(HttpCoverageReport.TOKEN).statusCode()).isEqualTo(400);
    }

    @Test
    void a_workspace_run_enters_at_the_roll_up_and_serves_every_module_page() throws Exception {
        Path ws = Files.createDirectories(stateDir.resolve("ws2"));
        for (String m : List.of("core", "app")) {
            Path dir = Files.createDirectories(ws.resolve(m + "/target/site/jacoco"));
            Files.writeString(dir.resolve("index.html"), "<html>" + m + "</html>");
        }
        Path rollup = Files.createDirectories(ws.resolve("target/site/jacoco"));
        Files.writeString(rollup.resolve("index.html"), "<html>roll-up</html>");
        String id = finished(testJournal(), ws, List.of(module(ws, "core", 10, 10), module(ws, "app", 5, 5)));
        String rt = reportToken(id);

        HttpResponse<String> entry = fetch("/report/" + rt + "/" + id + "/");
        assertThat(landing("/report/" + rt + "/" + id + "/", entry))
                .isEqualTo("/report/" + rt + "/" + id + "/target/site/jacoco/index.html");
        assertThat(fetch("/report/" + rt + "/" + id + "/target/site/jacoco/index.html")
                        .body())
                .contains("roll-up");
        assertThat(fetch("/report/" + rt + "/" + id + "/app/target/site/jacoco/index.html")
                        .body())
                .contains("app");
    }

    @Test
    void a_symlink_under_the_report_dir_that_points_outside_is_refused() throws Exception {
        Path ws = Files.createDirectories(stateDir.resolve("ws3"));
        Path report = Files.createDirectories(ws.resolve("lib/target/site/jacoco"));
        Files.writeString(report.resolve("index.html"), "<html>lib</html>");
        Path secret = Files.writeString(ws.resolve("lib/target/secret.txt"), "not a report");
        Symlinks.create(report.resolve("leak.txt"), secret);
        String id = finished(testJournal(), ws, List.of(module(ws, "lib", 1, 1)));
        String rt = reportToken(id);

        assertThat(fetch("/report/" + rt + "/" + id + "/lib/target/site/jacoco/leak.txt")
                        .statusCode())
                .isEqualTo(404);
        assertThat(fetch("/report/" + rt + "/" + id + "/lib/target/site/jacoco/index.html")
                        .statusCode())
                .isEqualTo(200);
    }

    @Test
    void a_report_file_past_the_size_cap_is_a_413() throws Exception {
        Path ws = Files.createDirectories(stateDir.resolve("ws4"));
        Path report = Files.createDirectories(ws.resolve("lib/target/site/jacoco"));
        Files.writeString(report.resolve("index.html"), "<html>lib</html>");
        Files.write(report.resolve("huge.html"), new byte[(int) HttpCoverageReport.MAX_FILE_BYTES + 1]);
        String id = finished(testJournal(), ws, List.of(module(ws, "lib", 1, 1)));
        String rt = reportToken(id);

        assertThat(fetch("/report/" + rt + "/" + id + "/lib/target/site/jacoco/huge.html")
                        .statusCode())
                .isEqualTo(413);
    }

    /** The path a browser that asked for {@code path} follows {@code redirect}'s {@code Location} to. */
    private String landing(String path, HttpResponse<String> redirect) {
        return URI.create(baseUrl + path.substring(1))
                .resolve(redirect.headers().firstValue("Location").orElseThrow())
                .getRawPath();
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

    static BuildRecord.Coverage module(Path ws, String name, long covered, long missed) {
        return new BuildRecord.Coverage(
                ws.resolve(name).toString(),
                "g:" + name,
                covered,
                missed,
                covered / 2,
                missed / 2,
                ws.resolve(name + "/target/site/jacoco/index.html").toString());
    }

    /** One finished coverage run of {@code ws} in the journal; returns a locator the route accepts. */
    static String finished(BuildJournal journal, Path ws, List<BuildRecord.Coverage> coverage) throws Exception {
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
