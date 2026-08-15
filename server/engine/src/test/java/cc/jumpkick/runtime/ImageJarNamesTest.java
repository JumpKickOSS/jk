// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.lock.Lockfile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Image jar names must keep the classifier (per-arch natives are distinct lock rows),
 * disambiguate same-name rows with the group, and refuse a residual collision instead of letting
 * the tar layer silently keep one of the two.
 */
class ImageJarNamesTest {

    private static Lockfile.Artifact row(String name, String version) {
        return new Lockfile.Artifact(name, version, "central", "sha256:" + name.hashCode(), "p", List.of());
    }

    @Test
    void classifier_variants_of_one_ga_get_distinct_names() throws Exception {
        Map<Path, Lockfile.Artifact> rows = new LinkedHashMap<>();
        rows.put(Path.of("cas/aa"), row("io.netty:netty-transport-native-epoll:jar:linux-x86_64", "4.1.100"));
        rows.put(Path.of("cas/bb"), row("io.netty:netty-transport-native-epoll:jar:linux-aarch_64", "4.1.100"));
        Map<Path, String> names = ImagePlans.jarNames(rows);
        assertThat(names.values())
                .containsExactlyInAnyOrder(
                        "netty-transport-native-epoll-4.1.100-linux-x86_64.jar",
                        "netty-transport-native-epoll-4.1.100-linux-aarch_64.jar");
    }

    @Test
    void same_artifact_under_two_groups_is_qualified_by_group() throws Exception {
        Map<Path, Lockfile.Artifact> rows = new LinkedHashMap<>();
        rows.put(Path.of("cas/aa"), row("com.foo:util:jar:", "1.0"));
        rows.put(Path.of("cas/bb"), row("org.bar:util:jar:", "1.0"));
        Map<Path, String> names = ImagePlans.jarNames(rows);
        assertThat(names.get(Path.of("cas/aa"))).isEqualTo("com.foo-util-1.0.jar");
        assertThat(names.get(Path.of("cas/bb"))).isEqualTo("org.bar-util-1.0.jar");
    }

    @Test
    void residual_collision_fails_instead_of_overwriting() {
        Map<Path, Lockfile.Artifact> rows = new LinkedHashMap<>();
        rows.put(Path.of("cas/aa"), row("com.foo:util:jar:", "1.0"));
        rows.put(Path.of("cas/bb"), row("com.foo:util:test-jar:", "1.0"));
        assertThatThrownBy(() -> ImagePlans.jarNames(rows))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("collision");
    }

    @Test
    void plain_rows_keep_the_simple_name() throws Exception {
        Map<Path, Lockfile.Artifact> rows = new LinkedHashMap<>();
        rows.put(Path.of("cas/aa"), row("com.foo:lib:jar:", "2.3"));
        assertThat(ImagePlans.jarNames(rows)).containsEntry(Path.of("cas/aa"), "lib-2.3.jar");
    }
}
