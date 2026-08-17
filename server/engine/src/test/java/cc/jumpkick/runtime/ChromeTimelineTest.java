// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChromeTimelineTest {

    @Test
    void writes_valid_chrome_trace_under_target(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project);
        ChromeTimeline timeline = ChromeTimeline.open(project);
        assertThat(timeline).isNotNull();
        assertThat(timeline.file().toString().replace('\\', '/')).endsWith("target/jk-profile.json");

        timeline.complete("demo", "compile-main", "SUCCESS", 12);
        timeline.complete("demo", "run-tests", "SKIPPED", 3);
        assertThat(timeline.flush()).contains(timeline.file());

        String json = Files.readString(timeline.file());
        assertThat(json.trim()).startsWith("[");
        assertThat(json).contains("\"ph\":\"X\"");
        assertThat(json).contains("compile-main");
        assertThat(json).contains("SUCCESS");
        assertThat(json).contains("\"dur\":");
        // 12 ms → 12000 µs in chrome format
        assertThat(json).contains("12000");
    }

    @Test
    void no_timeline_flag_disables_open(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project);
        assertThat(ChromeTimeline.open(project, true)).isNull();
    }
}
