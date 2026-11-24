// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.PluginTuning;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which resident compiler pool a request lands on. */
class JavaCompilerHostTest {

    @Test
    void a_request_with_worker_flags_gets_a_resident_pool_of_its_own(@TempDir Path dir) {
        ForkedJavac.Request req = request(dir);
        PluginTuning flagged = new PluginTuning(null, null, null, List.of("-Xmx3g", "-javaagent:/x/agent.jar"));
        PluginTuning heap = new PluginTuning(40.0, null, null, List.of());

        String plain = JavaCompilerHost.residentKey(req, PluginTuning.NONE);
        assertThat(JavaCompilerHost.residentKey(req, PluginTuning.NONE))
                .as("requests without flags share one worker")
                .isEqualTo(plain);
        assertThat(JavaCompilerHost.residentKey(req, flagged))
                .as("extra flags never reach the plain requests' worker")
                .isNotEqualTo(plain);
        assertThat(JavaCompilerHost.residentKey(req, heap))
                .as("a heap share never reaches the plain requests' worker")
                .isNotEqualTo(plain)
                .isNotEqualTo(JavaCompilerHost.residentKey(req, flagged));
    }

    private static ForkedJavac.Request request(Path dir) {
        return new ForkedJavac.Request(
                null,
                dir.resolve("worker.jar"),
                List.of(dir.resolve("C.java")),
                List.of(),
                List.of(),
                dir.resolve("classes"),
                dir.resolve("gen"),
                21,
                List.of());
    }
}
