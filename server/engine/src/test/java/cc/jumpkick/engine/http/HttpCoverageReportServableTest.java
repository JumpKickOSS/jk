// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.testing.Symlinks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which path a coverage report request is served from, without a server. */
class HttpCoverageReportServableTest {

    @Test
    void a_report_file_is_served_from_the_real_path_that_was_checked(@TempDir Path ws) throws Exception {
        Path report = Files.createDirectories(ws.resolve("lib/target/site/jacoco"));
        Files.writeString(report.resolve("index.html"), "<html>lib</html>");
        Symlinks.create(report.resolve("alias.html"), Path.of("index.html"));
        Path secret = Files.writeString(ws.resolve("lib/target/secret.txt"), "not a report");
        Symlinks.create(report.resolve("leak.txt"), secret);
        BuildRecord record = BuildRecord.running(1, "test", ws.toString(), "g:ws", "p1", 0L, "9.9", "cli")
                .withCoverage(List.of(new BuildRecord.Coverage(
                        ws.resolve("lib").toString(),
                        "g:lib",
                        1,
                        1,
                        0,
                        0,
                        report.resolve("index.html").toString())));

        Path index = report.resolve("index.html").toRealPath();
        assertThat(HttpCoverageReport.servable(record, "lib/target/site/jacoco/alias.html"))
                .isEqualTo(index);
        assertThat(HttpCoverageReport.servable(record, "lib/target/site/jacoco/index.html"))
                .isEqualTo(index);
        assertThat(HttpCoverageReport.servable(record, "lib/target/site/jacoco/leak.txt"))
                .isNull();
    }
}
