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
        assertThat(PortablePath.key(jdk)).startsWith(jdk.startsWith(JkDirs.store()) ? "store:" : "jk-home:");
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
}
