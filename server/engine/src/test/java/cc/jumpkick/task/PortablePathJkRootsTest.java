// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.host.JkDirs;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A record names a file under jk's own roots relative to the root, so the same dependency reads the
 * same on every machine and the form that may leave the machine keeps it. Key material, which
 * spells such a path by its last two segments, is unchanged.
 */
class PortablePathJkRootsTest {

    @Test
    void a_path_under_a_jk_root_is_named_relative_to_it(@TempDir Path tmp) {
        Path jar = JkDirs.store().resolve("repos/central/org/acme/lib/1.0/lib-1.0.jar");
        Path blob = JkDirs.cache().resolve("sha256/ab/cd/" + "e".repeat(60));
        Path jdk = JkDirs.home().resolve("jdks/temurin-25/lib/modules");
        Path elsewhere = tmp.resolve("libs/odd.jar");

        assertThat(PortablePath.key(jar)).isEqualTo("store:repos/central/org/acme/lib/1.0/lib-1.0.jar");
        assertThat(PortablePath.key(blob)).isEqualTo("cache:sha256/ab/cd/" + "e".repeat(60));
        assertThat(PortablePath.key(jdk))
                .startsWith(
                        jdk.startsWith(JkDirs.store())
                                ? "store:"
                                : jdk.startsWith(JkDirs.jdks()) ? "jdks:" : "jk-home:");
        assertThat(PortablePath.key(elsewhere))
                .isEqualTo(elsewhere.toAbsolutePath().toString().replace('\\', '/'));
        assertThat(PortablePath.of(jar)).as("key material is as it was").isEqualTo("1.0/lib-1.0.jar");
    }

    @Test
    void the_shared_form_keeps_classpath_and_worker_inputs_named_under_a_jk_root() {
        String key = "k".repeat(64);
        Path jar = JkDirs.store().resolve("repos/central/org/acme/lib/1.0/lib-1.0.jar");
        Map<String, String> inputs = Map.of(
                "src/A.java",
                "aaa",
                "cp:" + PortablePath.key(jar),
                "abi:1",
                "worker:" + PortablePath.key(JkDirs.store().resolve("sha256/ab/cd/" + "f".repeat(60))),
                "file:2");
        String local = ActionRecordText.render(
                "compile-main@x",
                key,
                inputs,
                Map.of("A.class", "a".repeat(64)),
                Set.of(),
                List.of(),
                ActionRecordText.Meta.now(
                        "java:compile", ActionRecordText.Writes.OUTPUTS, "jk/test", "linux-x86_64", -1, Map.of()));

        Map<String, String> shared = ActionRecordText.parse(
                        ActionRecordText.renderShared(ActionRecordText.parse(local, key)), key)
                .inputs();

        assertThat(shared)
                .containsEntry("cp:store:repos/central/org/acme/lib/1.0/lib-1.0.jar", "abi:1")
                .containsEntry("worker:store:sha256/ab/cd/" + "f".repeat(60), "file:2");
        assertThat(String.join("\n", shared.keySet()))
                .doesNotContain(JkDirs.home().toString());
    }
    /**
     * The roots a suite's stamp met by absolute path in the dojo-4 study, each by its word: a POM in
     * the Maven local repository (jk-engine, 328 of them), a library jk unpacked into its state
     * (jumpkick-intellij's {@code -Djna.boot.library.path}), a JDK jk installed; jk's own root names
     * a path the jk home holds too (JK-FNM8N).
     */
    @Test
    void the_maven_repository_and_jks_state_and_jdks_are_named_by_their_word(@TempDir Path tmp) {
        String was = System.getProperty("jk.m2.local");
        System.setProperty("jk.m2.local", tmp.resolve("m2").toString());
        try {
            Path pom = tmp.resolve("m2/org/scala-sbt/zinc-core_3/2.0.4/zinc-core_3-2.0.4.pom");
            Path unpacked = JkDirs.state().resolve("tmp/unpacked/4d25ba9308df/lib/nio-fs.jar");
            Path jdk = JkDirs.jdks().resolve("temurin-25/release");

            assertThat(PortablePath.key(pom)).isEqualTo("m2:org/scala-sbt/zinc-core_3/2.0.4/zinc-core_3-2.0.4.pom");
            assertThat(PortablePath.key(unpacked)).isEqualTo("state:tmp/unpacked/4d25ba9308df/lib/nio-fs.jar");
            assertThat(PortablePath.key(jdk)).isEqualTo("jdks:temurin-25/release");
            assertThat(PortablePath.key(JkDirs.store().resolve("tools/node/24.22.0/bin/node")))
                    .as("the store, though it lies in the jk home")
                    .isEqualTo("store:tools/node/24.22.0/bin/node");
            for (Path p : List.of(pom, unpacked, jdk)) {
                assertThat(PortablePath.fromJkRoot(PortablePath.key(p)))
                        .isEqualTo(p.toAbsolutePath().normalize());
            }
        } finally {
            if (was == null) System.clearProperty("jk.m2.local");
            else System.setProperty("jk.m2.local", was);
        }
    }

    /** A JVM flag that names a path under a root or the checkout reads the same from any home and checkout. */
    @Test
    void a_flag_naming_a_path_under_a_root_or_the_checkout_is_spelled_by_its_word(@TempDir Path tmp) {
        Path ws = tmp.resolve("ws");
        String flags = "-Djna.boot.library.path=" + JkDirs.state().resolve("tmp/unpacked/4d25/lib") + " -Dfixture="
                + ws.resolve("data/a.json") + " -Duser.language=en";

        // Only the root is replaced: the rest of the path keeps this host's separators.
        // TODO(JK-QX08F): spell the rest alike on every OS, so the flag keys alike on Windows and Unix
        assertThat(PortablePath.spelledText(flags, ws))
                .isEqualTo("-Djna.boot.library.path=state:" + Path.of("tmp/unpacked/4d25/lib") + " -Dfixture=ws:"
                        + Path.of("data/a.json") + " -Duser.language=en");
    }
}
