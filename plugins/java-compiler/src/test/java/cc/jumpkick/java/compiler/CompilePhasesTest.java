// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompilePhasesTest {

    @Test
    void a_job_with_a_sink_appends_one_line_per_module(@TempDir Path dir) throws Exception {
        Path sink = dir.resolve("phases.log");
        CompilePhases phases = CompilePhases.open(sink);
        phases.mark("processors");
        phases.mark("zinc.compile");
        phases.write(dir.resolve("classes"), 7);

        assertThat(Files.readAllLines(sink)).hasSize(1);
        assertThat(Files.readString(sink))
                .startsWith("module=" + dir.resolve("classes") + " sources=7 ")
                .contains("processors=")
                .contains("zinc.compile=");
    }

    @Test
    void a_job_without_a_sink_writes_nothing(@TempDir Path dir) throws Exception {
        CompilePhases phases = CompilePhases.open(null);
        phases.mark("processors");
        phases.write(dir.resolve("classes"), 7);
        assertThat(Files.list(dir)).isEmpty();
    }
}
