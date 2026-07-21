// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.StepStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChromeTimelineTest {

    @Test
    void writes_valid_chrome_trace_with_complete_events(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("profile.json");
        System.setProperty("unused", "x"); // keep env-based open path via absolute override
        // Use open via absolute path: temporarily set via reflection-free API —
        // ChromeTimeline.open uses env; write through constructor path by public open after symlink
        // Instead: call complete via listener with a forced open by writing DEFAULT under project.
        Path project = dir.resolve("proj");
        Files.createDirectories(project);
        ChromeTimeline timeline = ChromeTimeline.open(project);
        // When JK_CHROME_PROFILE is unset, file is out/jk-chrome-profile.json under project.
        org.junit.jupiter.api.Assumptions.assumeTrue(timeline != null, "timeline disabled by env");

        var lis = new ChromeTimelineListener(timeline, "demo", true);
        lis.stepStart("compile-main", Phase.COMPILE, 10);
        Thread.sleep(5);
        lis.stepFinish("compile-main", Phase.COMPILE, StepStatus.SUCCESS, Duration.ofMillis(5));
        lis.stepStart("run-tests", Phase.TEST, 10);
        Thread.sleep(2);
        lis.stepFinish("run-tests", Phase.TEST, StepStatus.SKIPPED, Duration.ofMillis(2));
        lis.pipelineFinish(new PipelineResult(
                "test-pipe", true, Duration.ZERO, List.of(), List.of(), List.of(), false));

        Path file = timeline.file();
        assertTrue(Files.isRegularFile(file), "expected " + file);
        String json = Files.readString(file);
        assertTrue(json.trim().startsWith("["), json);
        assertTrue(json.contains("\"ph\":\"X\""), json);
        assertTrue(json.contains("compile-main"), json);
        assertTrue(json.contains("SUCCESS") || json.contains("SKIPPED"), json);
        assertTrue(json.contains("\"dur\":"), json);
    }

    @Test
    void no_timeline_thread_flag_disables_open(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project);
        ChromeTimeline.disableForThread();
        try {
            org.junit.jupiter.api.Assertions.assertNull(ChromeTimeline.open(project));
        } finally {
            ChromeTimeline.clearDisabled();
        }
        // After clear, open works again (unless env disables)
        ChromeTimeline t = ChromeTimeline.open(project);
        org.junit.jupiter.api.Assumptions.assumeTrue(t != null);
        org.junit.jupiter.api.Assertions.assertNotNull(t.file());
    }
}
