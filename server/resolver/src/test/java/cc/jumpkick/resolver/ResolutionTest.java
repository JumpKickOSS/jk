// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import org.junit.jupiter.api.Test;

/**
 * The module map iterates in one order in every JVM. Nine callers walk {@code modules().values()}
 * and some of them turn the walk into an ordered artifact (a worker classpath, a cache key), so a
 * salted order would move that artifact per process. Asserting the type, not a sample order: a
 * regression to a salted map would otherwise pass one run in N.
 */
class ResolutionTest {

    private static Resolution.ResolvedModule module(String key) {
        return new Resolution.ResolvedModule(key, "1.0", List.of(), Map.of(), List.of());
    }

    @Test
    void modules_iterate_in_natural_key_order_whatever_the_insertion_order() {
        Map<String, Resolution.ResolvedModule> insertion = new LinkedHashMap<>();
        for (String key : List.of("org.z:last", "com.a:first", "org.m:middle")) insertion.put(key, module(key));

        Resolution resolution = new Resolution(insertion);

        assertThat(resolution.modules()).isInstanceOf(SortedMap.class);
        assertThat(resolution.modules().keySet()).containsExactly("com.a:first", "org.m:middle", "org.z:last");
    }

    @Test
    void the_map_is_a_copy_and_cannot_be_changed() {
        Map<String, Resolution.ResolvedModule> source = new LinkedHashMap<>();
        source.put("com.a:first", module("com.a:first"));
        Resolution resolution = new Resolution(source);
        source.put("org.z:late", module("org.z:late"));

        assertThat(resolution.modules()).containsOnlyKeys("com.a:first");
        assertThatThrownBy(() -> resolution.modules().put("x:y", module("x:y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
