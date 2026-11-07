// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.api.GraalResolver;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk run} of a module that links a native image sends the GraalVM the client resolved, as
 * {@code jk build} does: the engine is a daemon, and a GraalVM it picked for itself would answer
 * from the shell that started it.
 */
class RunVerbGraalTest {

    @Test
    void a_native_module_run_sends_the_clients_graalvm_home(@TempDir Path tmp) throws IOException {
        Path dir = tmp.toAbsolutePath().normalize();
        Files.writeString(
                dir.resolve("jk.toml"),
                "name = \"cli\"\njava = 25\n\n[application]\nmain = \"c.Main\"\nnative = true\n");
        Path home = Path.of("/jdks/graalvm-25");
        List<String> asked = new ArrayList<>();
        RunVerb verb = new RunVerb(List.of(), null);

        int code = verb.prepared(
                AlwaysNativeGraal.reached(true, dir, BuildRun.Selection.NONE, () -> null, (d, spec, release) -> {
                    asked.add(d + " " + spec + " " + release);
                    return Optional.of(GraalResolver.Choice.found(home));
                }));

        assertThat(code).isEqualTo(-1);
        assertThat(asked).containsExactly(dir + " graalvm 25");
        WorkspaceSpec spec = verb.spec();
        assertThat(spec.target()).isEqualTo(WorkspaceTarget.RUN);
        assertThat(spec.graalByDir()).isEqualTo(Map.of(dir, home));
        assertThat(spec.graalInstalls()).isEmpty();
    }

    @Test
    void a_graalvm_to_install_travels_as_its_spec_and_an_unresolved_one_stops_the_run(@TempDir Path tmp)
            throws IOException {
        Path dir = tmp.toAbsolutePath().normalize();
        Files.writeString(
                dir.resolve("jk.toml"), "name = \"cli\"\n\n[native]\nenabled = \"always\"\ngraal = \"graalvm-25\"\n");

        RunVerb installs = new RunVerb(List.of(), null);
        assertThat(installs.prepared(AlwaysNativeGraal.reached(
                        true,
                        dir,
                        BuildRun.Selection.NONE,
                        () -> null,
                        (d, spec, release) -> Optional.of(GraalResolver.Choice.toInstall("graalvm-25")))))
                .isEqualTo(-1);
        assertThat(installs.spec().graalInstalls()).isEqualTo(Map.of(dir, "graalvm-25"));

        RunVerb refused = new RunVerb(List.of(), null);
        assertThat(refused.prepared(AlwaysNativeGraal.reached(
                        true, dir, BuildRun.Selection.NONE, () -> null, (d, spec, release) -> Optional.empty())))
                .isEqualTo(Exit.FAILURE);
    }

    @Test
    void a_jvm_module_run_sends_no_graalvm_and_asks_for_none(@TempDir Path tmp) throws IOException {
        Path dir = tmp.toAbsolutePath().normalize();
        Files.writeString(dir.resolve("jk.toml"), "name = \"app\"\n\n[application]\nmain = \"a.Main\"\n");
        RunVerb verb = new RunVerb(List.of(), null);

        assertThat(verb.prepared(
                        AlwaysNativeGraal.reached(true, dir, BuildRun.Selection.NONE, () -> null, (d, s, r) -> {
                            throw new AssertionError("a JVM module resolves no GraalVM");
                        })))
                .isEqualTo(-1);
        assertThat(verb.spec()).isEqualTo(WorkspaceSpec.of(WorkspaceTarget.RUN));
    }
}
