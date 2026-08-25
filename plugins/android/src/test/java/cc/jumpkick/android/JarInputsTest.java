// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code .jar}-alias shim in front of d8/R8: the tools judge inputs by file extension, and a
 * runtime entry served out of the content-addressed store has none — so it gets a suffixed alias
 * under the step's scratch, while real {@code .jar}/{@code .zip} paths pass through untouched.
 */
class JarInputsTest {

    /** Suffixed entries pass through as the same path; extensionless ones come back aliased. */
    @Test
    void only_extensionless_entries_are_aliased_and_order_is_kept(@TempDir Path tmp) throws Exception {
        FakeBuildIo exec = AndroidIo.step(tmp);
        Path jar = Files.writeString(tmp.resolve("lib.jar"), "jar-bytes");
        Path zip = Files.writeString(tmp.resolve("kit.zip"), "zip-bytes");
        Path bare = Files.writeString(tmp.resolve("0a1b2c3d"), "cas-bytes");

        List<Path> out = JarInputs.jarSuffixed(exec, List.of(jar, bare, zip));

        assertThat(out).hasSize(3);
        assertThat(out.get(0)).isSameAs(jar);
        assertThat(out.get(2)).isSameAs(zip);
        assertThat(out.get(1))
                .as("the alias is index-named under scratch so two same-named inputs cannot collide")
                .isEqualTo(exec.scratch().resolve("rt-jars/rt-1-0a1b2c3d.jar"));
        assertThat(out.get(1).getFileName().toString()).endsWith(".jar");
        assertThat(Files.readString(out.get(1)))
                .as("link or copy, the tool reads the same bytes")
                .isEqualTo("cas-bytes");
    }

    /** {@code jarNamed} is the single-tool form: one alias, caller-chosen name, under scratch. */
    @Test
    void a_named_alias_lands_under_the_steps_scratch_tools_dir(@TempDir Path tmp) throws Exception {
        FakeBuildIo exec = AndroidIo.step(tmp);
        Path source = Files.writeString(tmp.resolve("r8-blob"), "r8-bytes");

        Path alias = JarInputs.jarNamed(exec, source, "r8.jar");

        assertThat(alias).isEqualTo(exec.scratch().resolve("tools/r8.jar"));
        assertThat(Files.readString(alias)).isEqualTo("r8-bytes");
    }

    /** A re-run aliases again over the previous link — a stale alias must never survive. */
    @Test
    void aliasing_twice_replaces_the_previous_alias(@TempDir Path tmp) throws Exception {
        FakeBuildIo exec = AndroidIo.step(tmp);
        Path first = Files.writeString(tmp.resolve("first-blob"), "first");
        JarInputs.jarNamed(exec, first, "tool.jar");
        Path second = Files.writeString(tmp.resolve("second-blob"), "second");

        Path alias = JarInputs.jarNamed(exec, second, "tool.jar");

        assertThat(Files.readString(alias)).isEqualTo("second");
    }
}
