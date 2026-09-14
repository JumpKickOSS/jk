// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.PluginSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the engine writes into a compile spec on the request's behalf. */
class ForkedJavacSpecTest {

    @Test
    void the_shell_s_phases_sink_rides_the_spec_and_an_unset_one_leaves_no_trace(@TempDir Path dir) throws Exception {
        ForkedJavac.Request req = request(dir);
        Path sink = dir.resolve("phases.log");

        Session asking =
                Session.defaults().withVariant(null, Map.of(PluginProtocol.COMPILE_PHASES_ENV, sink.toString()));
        Path spec = SessionContext.where(asking, () -> ForkedJavac.writeSpec(req));
        assertThat(Files.readString(spec))
                .contains("\"" + PluginProtocol.CONFIG_PHASES_LOG + "\"")
                .contains(sink.toString().replace("\\", "\\\\"));

        Path plain = SessionContext.where(Session.defaults(), () -> ForkedJavac.writeSpec(req));
        assertThat(Files.readString(plain)).doesNotContain(PluginProtocol.CONFIG_PHASES_LOG);
    }

    @Test
    void producer_analyses_ride_the_spec_as_cp_analysis_lines(@TempDir Path dir) throws Exception {
        Path lib = dir.resolve("lib.jar");
        Path analysis = dir.resolve("state").resolve("zinc");
        ForkedJavac.Request informed = request(dir).withClasspathAnalyses(Map.of(lib, analysis));

        Path spec = SessionContext.where(Session.defaults(), () -> ForkedJavac.writeSpec(informed));
        assertThat(PluginSpec.read(spec).classpathAnalyses())
                .containsExactly(Map.entry(lib.toAbsolutePath(), analysis.toAbsolutePath()));

        Path plain = SessionContext.where(Session.defaults(), () -> ForkedJavac.writeSpec(request(dir)));
        assertThat(Files.readString(plain)).doesNotContain(PluginProtocol.CP_ANALYSIS);
    }

    private static ForkedJavac.Request request(Path dir) {
        Path root = dir.resolve("m");
        return new ForkedJavac.Request(
                null,
                root.resolve("worker.jar"),
                List.of(root.resolve("C.java")),
                List.of(),
                List.of(),
                root.resolve("classes"),
                root.resolve("gen"),
                21,
                List.of());
    }
}
