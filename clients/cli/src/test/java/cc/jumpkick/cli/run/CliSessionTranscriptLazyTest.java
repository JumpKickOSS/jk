// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.WireStream;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A build over before its transcript is old enough to tail writes the file once, at finish; a
 * no-op, whose run has no directory, writes none.
 */
class CliSessionTranscriptLazyTest {

    @BeforeEach
    void longDelay() {
        CliSessionTranscript.lazyOpenMillis = 3_600_000L;
    }

    @AfterEach
    void reset() {
        CliSessionTranscript.lazyOpenMillis = CliSessionTranscript.LAZY_OPEN_MILLIS;
        WireStream.noteJobFinish(ProtoLifecycle.jobFinish(0));
        CliSessionTranscript leftover = CliSessionTranscript.active();
        if (leftover != null) leftover.finish(0);
    }

    @Test
    void a_short_build_writes_its_transcript_once_at_finish(@TempDir Path project) throws Exception {
        CliSessionTranscript session =
                Objects.requireNonNull(CliSessionTranscript.open(project, "build", List.of("build")));
        Path details = project.resolve("runs").resolve("7").resolve(CliSessionTranscript.FILE_NAME);
        session.bindJob(42, 7, details.toString(), -1);
        session.append("{\"type\":\"task-start\",\"task\":\"compile-java\"}", true);

        assertThat(details.getParent()).doesNotExist();

        WireStream.noteJobFinish(ProtoLifecycle.jobFinish(42));
        assertThat(session.finish(0)).contains(details);
        List<String> lines = Files.readAllLines(details);
        assertThat(lines.getFirst()).contains("\"type\":\"session-start\"");
        assertThat(lines).anyMatch(l -> l.contains("compile-java"));
        assertThat(lines.getLast()).contains("\"type\":\"session-finish\"");
    }

    @Test
    void a_no_op_leaves_no_transcript_and_no_run_directory(@TempDir Path project) throws Exception {
        CliSessionTranscript session =
                Objects.requireNonNull(CliSessionTranscript.open(project, "build", List.of("build")));
        Path details = project.resolve("runs").resolve("8").resolve(CliSessionTranscript.FILE_NAME);
        session.bindJob(43, 8, details.toString(), -1);

        WireStream.noteJobFinish(ProtoLifecycle.jobFinish(43, "OK build app · 90ms · up to date\n"));

        assertThat(session.finish(0)).isEmpty();
        assertThat(details.getParent()).doesNotExist();
        assertThat(WireStream.lastJobNoop()).contains("OK build app · 90ms · up to date\n");
    }
}
