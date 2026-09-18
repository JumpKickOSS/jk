// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.jdk.JavaHomes;
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

    @Test
    void a_J_argument_starts_the_worker_jvm_and_never_reaches_javac(@TempDir Path dir) throws Exception {
        // javac's launcher spelling: -J<flag> is for the JVM running the compiler, not for javac,
        // which rejects it as an invalid flag. The importer keeps Error Prone's -J--add-exports
        // lines verbatim, so the engine has to split them the way the launcher does.
        String granted = "-J" + JdkCompilerAccess.JVM_FLAGS.getFirst();
        ForkedJavac.Request req = request(
                dir, List.of("-Xlint:all", granted, "-J--add-opens=java.base/java.lang=ALL-UNNAMED", "-J-Dprobe=1"));

        assertThat(req.javacArgs()).containsExactly("-Xlint:all");
        assertThat(req.jvmArgs())
                .containsExactly(
                        JdkCompilerAccess.JVM_FLAGS.getFirst(),
                        "--add-opens=java.base/java.lang=ALL-UNNAMED",
                        "-Dprobe=1");
        Path spec = SessionContext.where(Session.defaults(), () -> ForkedJavac.writeSpec(req));
        assertThat(PluginSpec.read(spec).args()).containsExactly("-Xlint:all");

        List<String> flags = ForkedJavac.workerJvmFlags(List.of(), null, req.jvmArgs());
        assertThat(flags).contains("--add-opens=java.base/java.lang=ALL-UNNAMED", "-Dprobe=1");
        assertThat(flags.stream().filter(JdkCompilerAccess.JVM_FLAGS.getFirst()::equals))
                .as("a flag the worker grants anyway is not passed twice")
                .hasSize(1);
        assertThat(request(dir, List.of("-Xlint:all")).jvmArgs()).isEmpty();
    }

    /**
     * A worker started with a module's own JVM flags maps a cache trained under the same flags:
     * the flags that are not jk's own are what joins the AOT key, and the trainer's command
     * carries them.
     */
    @Test
    void a_modules_novel_J_flags_join_the_aot_key_and_the_trainer_command(@TempDir Path dir) throws Exception {
        String granted = "-J" + JdkCompilerAccess.JVM_FLAGS.getFirst();
        ForkedJavac.Request plain = request(dir, List.of("-Xlint:all", granted));
        ForkedJavac.Request novel =
                request(dir, List.of(granted, "-J--add-opens=java.base/java.lang=ALL-UNNAMED", "-J-Dprobe=1"));

        assertThat(ForkedJavac.novelJvmArgs(plain))
                .as("a flag jk grants anyway is not novel")
                .isEmpty();
        assertThat(ForkedJavac.novelJvmArgs(novel))
                .containsExactly("--add-opens=java.base/java.lang=ALL-UNNAMED", "-Dprobe=1");

        Path host = requireNonNull(JavaHomes.runningJavaHome());
        Path scratch = Files.createDirectories(dir.resolve("scratch"));
        List<String> trainer = ForkedJavac.trainerCommand(novel, "w.jar", host, dir.resolve("out.aot"), scratch);
        assertThat(trainer).contains("--add-opens=java.base/java.lang=ALL-UNNAMED", "-Dprobe=1");
        assertThat(trainer.stream().filter(JdkCompilerAccess.JVM_FLAGS.getFirst()::equals))
                .hasSize(1);
    }

    private static ForkedJavac.Request request(Path dir) {
        return request(dir, List.of());
    }

    private static ForkedJavac.Request request(Path dir, List<String> extraArgs) {
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
                extraArgs);
    }
}
