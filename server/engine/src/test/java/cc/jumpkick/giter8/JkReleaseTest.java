// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.winhost.ReleasePin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code $jk_release$}: a development build names the newest published release at or below it, so
 * the plugin template's SDK pin, which only a release publishes, never names a {@code -SNAPSHOT}
 * that the library pack's {@code no-snapshot-versions} refuses (JK-CBKBT).
 */
class JkReleaseTest {

    @Test
    void a_development_build_names_the_newest_release_at_or_below_it() {
        Set<String> site = Set.of("0.51.1", "0.52.0");
        assertThat(JkRelease.of("0.52.1-SNAPSHOT", site::contains, () -> "0.52.0"))
                .isEqualTo("0.52.0");
        // A new line's first snapshot falls back to the latest pointer, when it is not newer.
        assertThat(JkRelease.of("0.53.0-SNAPSHOT", site::contains, () -> "0.52.0"))
                .isEqualTo("0.52.0");
        assertThat(JkRelease.of("0.52.0", v -> false, () -> "0.51.1")).isEqualTo("0.52.0");
        // Nothing fits, or nobody can say: the snapshot itself, as before.
        assertThat(JkRelease.of("0.52.1-SNAPSHOT", v -> false, () -> "0.60.0")).isEqualTo("0.52.1-SNAPSHOT");
        ReleasePin.Published offline = v -> {
            throw new IOException("offline");
        };
        assertThat(JkRelease.of("0.52.1-SNAPSHOT", offline, () -> "0.52.0")).isEqualTo("0.52.1-SNAPSHOT");
    }

    @Test
    void the_plugin_template_pins_the_sdk_and_its_compat_at_the_release(@TempDir Path tmp) throws Exception {
        Path template = RepoRoot.find(JkReleaseTest.class).resolve("templates/java/plugin.g8");
        Path dest = tmp.resolve("demo");
        Giter8Apply.apply(template, dest, Map.of("name", "demo"), null, () -> "0.52.0");

        assertThat(dest.resolve("jk.toml")).content().contains("jk-plugin-sdk = \"cc.jumpkick:jk-plugin-sdk:0.52.0\"");
        String plugin = Files.readString(dest.resolve("src/main/resources/jk-plugin.toml"));
        assertThat(plugin).contains("sdk       = \"0.52.0\"").contains("jk-compat = \">=0.52.0\"");
        assertThat(Files.readString(dest.resolve("jk.toml")) + plugin).doesNotContain("SNAPSHOT");
    }

    @Test
    void a_template_that_names_no_release_never_asks(@TempDir Path tmp) throws Exception {
        Path g8 = Files.createDirectories(tmp.resolve("t/src/main/g8"));
        Files.writeString(tmp.resolve("t/default.properties"), "name=demo\n");
        Files.writeString(g8.resolve("jk.toml"), "name = \"$name$\"\n# rule packs at $jk_version$\n");
        Path dest = tmp.resolve("out");

        JkRelease.Lookup never = () -> {
            throw new AssertionError("asked the release site for a template that names no $jk_release$");
        };
        Giter8Apply.apply(tmp.resolve("t"), dest, Map.of(), null, never);

        assertThat(dest.resolve("jk.toml")).content().contains("name = \"demo\"");
    }
}
