// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Coordinate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * JK-1661: the tool-closure CAS lookup is {@code Files.isDirectory(dir)} with no content check,
 * so two distinct closures sharing a directory name silently serve each other's jars.
 */
class ToolClosureCacheKeyTest {

    @Test
    void two_long_keys_differing_only_in_a_late_segment_do_not_collide() {
        // The realistic shape: same group, same `with` list, one version segment apart — so the
        // keys agree far past any prefix truncation.
        List<Coordinate> a = closure("1.2.3");
        List<Coordinate> b = closure("1.2.4");

        String keyA = PluginBuild.toolClosureCacheKey(a, "com.example.platform:platform-bom:9.9.9");
        String keyB = PluginBuild.toolClosureCacheKey(b, "com.example.platform:platform-bom:9.9.9");

        assertThat(keyA).hasSizeLessThan(200).isNotEqualTo(keyB);
    }

    @Test
    void a_differing_bom_alone_changes_the_key() {
        List<Coordinate> roots = closure("1.2.3");

        assertThat(PluginBuild.toolClosureCacheKey(roots, "com.example.platform:platform-bom:9.9.9"))
                .isNotEqualTo(PluginBuild.toolClosureCacheKey(roots, "com.example.platform:platform-bom:9.9.8"));
    }

    @Test
    void the_same_closure_names_the_same_directory_every_time() {
        assertThat(PluginBuild.toolClosureCacheKey(closure("1.2.3"), "com.example:bom:1.0"))
                .isEqualTo(PluginBuild.toolClosureCacheKey(closure("1.2.3"), "com.example:bom:1.0"));
    }

    @Test
    void a_short_key_stays_readable() {
        String key = PluginBuild.toolClosureCacheKey(List.of(Coordinate.of("com.android.tools", "r8", "8.5.35")), null);

        assertThat(key).isEqualTo("com.android.tools_r8_8.5.35");
    }

    @Test
    void a_hashed_key_is_still_a_safe_path_component() {
        String key = PluginBuild.toolClosureCacheKey(closure("1.2.3"), "com.example:bom:1.0");

        assertThat(key).doesNotContain("/").doesNotContain(":").doesNotContain("\\");
    }

    /** Enough roots to push the key past the 180-char readable limit. */
    private static List<Coordinate> closure(String version) {
        List<Coordinate> roots = new ArrayList<>();
        roots.add(Coordinate.of("com.example.tooling", "tool-cli", version));
        for (int i = 0; i < 6; i++) {
            roots.add(Coordinate.of("com.example.tooling", "tool-optimizer-module-" + i, version));
        }
        return roots;
    }
}
