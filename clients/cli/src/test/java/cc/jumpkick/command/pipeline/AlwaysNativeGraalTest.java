// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk build} resolves the GraalVM home of every always-native module on the client, from the
 * bootstrap TOML, and ships it; a module that pins no spec asks the resolver for the default.
 */
class AlwaysNativeGraalTest {

    @Test
    void always_native_members_are_read_from_the_bootstrap_toml(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("jk.toml"), "[workspace]\nmodules = [\"app\", \"lib\", \"tool\"]\n");
        write(
                root.resolve("app/jk.toml"),
                "[project]\nname = \"app\"\n\n[native]\nenabled = \"always\"\ngraal = \"graalvm-25\"\n");
        write(root.resolve("lib/jk.toml"), "[project]\nname = \"lib\"\n\n[native]\nenabled = true\n");
        write(
                root.resolve("tool/jk.toml"),
                "[project]\nname = \"tool\"\n\n[application]\nmain = \"t.Main\"\nnative = true\n");

        List<AlwaysNativeGraal.Module> modules = AlwaysNativeGraal.fromManifests(root);

        assertThat(modules)
                .containsExactly(
                        new AlwaysNativeGraal.Module(
                                root.toAbsolutePath().normalize().resolve("app"), "graalvm-25"),
                        new AlwaysNativeGraal.Module(
                                root.toAbsolutePath().normalize().resolve("tool"), AlwaysNativeGraal.DEFAULT_SPEC));
    }

    @Test
    void a_lone_module_is_its_own_member(@TempDir Path dir) throws IOException {
        write(dir.resolve("jk.toml"), "[project]\nname = \"cli\"\n\n[native]\nenabled = \"always\"\n");
        assertThat(AlwaysNativeGraal.fromManifests(dir))
                .containsExactly(
                        new AlwaysNativeGraal.Module(dir.toAbsolutePath().normalize(), AlwaysNativeGraal.DEFAULT_SPEC));
        write(dir.resolve("jk.toml"), "[project]\nname = \"cli\"\n");
        assertThat(AlwaysNativeGraal.fromManifests(dir)).isEmpty();
    }

    /**
     * {@code jk build -m api} in a workspace with an always-native sibling must not resolve — or
     * prompt for, or download — that sibling's Graal: the selector confines the build, so it
     * confines the resolution.
     */
    @Test
    void a_selector_confines_the_resolution_to_the_selected_members(@TempDir Path root) {
        var app = new AlwaysNativeGraal.Module(
                root.resolve("app").toAbsolutePath().normalize(), "graalvm-25");
        var tool = new AlwaysNativeGraal.Module(
                root.resolve("tool").toAbsolutePath().normalize(), "graalvm");
        List<AlwaysNativeGraal.Module> all = List.of(app, tool);

        assertThat(AlwaysNativeGraal.within(
                        all,
                        List.of(
                                root.resolve("tool").toString(),
                                root.resolve("api").toString())))
                .containsExactly(tool);
        assertThat(AlwaysNativeGraal.within(all, List.of(root.resolve("api").toString())))
                .isEmpty();
        assertThat(AlwaysNativeGraal.within(all, List.of(root + "/x/../app"))).containsExactly(app);
        assertThat(AlwaysNativeGraal.homes(List.of(), (dir, spec) -> {
                    throw new AssertionError("no member, no resolution");
                }))
                .contains(Map.of());
    }

    @Test
    void homes_stop_at_the_first_unresolved_pin() {
        var app = new AlwaysNativeGraal.Module(Path.of("/w/app"), "graalvm-25");
        var tool = new AlwaysNativeGraal.Module(Path.of("/w/tool"), "graalvm");
        Path home = Path.of("/jdks/graalvm-25");

        assertThat(AlwaysNativeGraal.homes(List.of(app, tool), (dir, spec) -> Optional.of(home)))
                .contains(Map.of(app.dir(), home, tool.dir(), home));
        assertThat(AlwaysNativeGraal.homes(
                        List.of(app, tool),
                        (dir, spec) -> spec.equals("graalvm") ? Optional.empty() : Optional.of(home)))
                .as("one unresolved pin fails the whole build up front")
                .isEmpty();
        assertThat(AlwaysNativeGraal.homes(List.of(), (dir, spec) -> Optional.empty()))
                .as("no native module, nothing to resolve")
                .contains(Map.of());
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
