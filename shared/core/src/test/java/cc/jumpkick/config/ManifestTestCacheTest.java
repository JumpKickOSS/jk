// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.TestCache;
import org.junit.jupiter.api.Test;

/** {@code [test] cache}: per class unless the module says its suite reruns whole, or replays by cone. */
class ManifestTestCacheTest {

    private static TestCache cache(String toml) {
        return JkBuildParser.parse("name = \"m\"\ngroup = \"g\"\nversion = \"1.0\"\n" + toml)
                .build()
                .testCache();
    }

    @Test
    void per_class_by_default_and_when_said() {
        assertThat(cache("")).isEqualTo(TestCache.CLASS);
        assertThat(cache("[test]\ncache = \"class\"\n")).isEqualTo(TestCache.CLASS);
    }

    @Test
    void module_reruns_the_suite_whole() {
        assertThat(cache("[test]\ncache = \"module\"\n")).isEqualTo(TestCache.MODULE);
    }

    @Test
    void cone_replays_what_nothing_reached() {
        assertThat(cache("[test]\ncache = \"cone\"\n")).isEqualTo(TestCache.CONE);
    }

    @Test
    void anything_else_is_refused() {
        assertThatThrownBy(() -> cache("[test]\ncache = \"suite\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].cache must be \"class\", \"module\" or \"cone\"");
    }
}
