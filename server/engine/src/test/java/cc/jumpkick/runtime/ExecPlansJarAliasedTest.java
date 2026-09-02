// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * jshell classpath aliasing runs in the RESIDENT engine, so it must be
 * idempotent against a stable cache dir — the old per-request temp dir + deleteOnExit
 * accumulated for the engine's whole lifetime.
 */
class ExecPlansJarAliasedTest {

    @Test
    void cas_blob_gets_a_stable_jar_alias_and_repeat_calls_reuse_it(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Path blob = tmp.resolve("ab12cd34ef");
        Files.writeString(blob, "jar-bytes");

        List<Path> first = ExecPlans.jarAliased(cache, List.of(blob));
        assertThat(first).hasSize(1);
        Path alias = first.get(0);
        assertThat(alias.getFileName().toString()).endsWith(".jar");
        assertThat(alias).exists();
        assertThat(alias.getParent()).isEqualTo(cache.resolve("jshell-cp"));

        List<Path> second = ExecPlans.jarAliased(cache, List.of(blob));
        assertThat(second).containsExactly(alias);
        try (var files = Files.list(cache.resolve("jshell-cp"))) {
            assertThat(files.count()).as("no per-request growth").isEqualTo(1);
        }
    }

    @Test
    void directories_and_jars_pass_through_untouched(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Path dir = Files.createDirectories(tmp.resolve("classes"));
        Path jar = tmp.resolve("lib.jar");
        Files.writeString(jar, "not a real jar");

        List<Path> out = ExecPlans.jarAliased(cache, List.of(dir, jar));

        assertThat(out).containsExactly(dir, jar);
        assertThat(cache.resolve("jshell-cp")).doesNotExist();
    }

    @Test
    void equal_names_from_different_roots_do_not_collide(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Path a = Files.createDirectories(tmp.resolve("a")).resolve("ff00");
        Path b = Files.createDirectories(tmp.resolve("b")).resolve("ff00");
        Files.writeString(a, "alpha");
        Files.writeString(b, "beta");

        List<Path> out = ExecPlans.jarAliased(cache, List.of(a, b));

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).isNotEqualTo(out.get(1));
        assertThat(Files.readString(out.get(0))).isEqualTo("alpha");
        assertThat(Files.readString(out.get(1))).isEqualTo("beta");
    }
}
