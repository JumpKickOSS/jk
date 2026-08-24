// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.surface;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * This module writes its own JSON string literals instead of calling {@code Jsonl.quote}, and that
 * duplication is deliberate: the module exists to be linkable by both the minified worker and the
 * native-image driver, which requires it to stay dependency-free (see the module's build
 * description). The obligation that comes with the exemption is that its escaping agrees with
 * {@code Jsonl.quote} character for character, so a surface written here is readable by everything
 * else. This test is what pins that.
 *
 * <p>The expected forms are restated here rather than imported on purpose — if this module could
 * import the owner it would not need its own writer at all.
 */
class DynamicSurfaceIoJsonTest {

    @Test
    void control_characters_are_escaped_not_passed_through() {
        // origin is free text (e.g. "train:<profile>"), so it can carry anything the caller had.
        assertThat(json("a\u001bb")).contains("\"a\\u001bb\"");
        assertThat(json("a\u000bb")).contains("\"a\\u000bb\"");
        assertThat(json("a\u0007b")).contains("\"a\\u0007b\"");
        assertThat(json("\u0000")).contains("\"\\u0000\"");
    }

    @Test
    void the_named_escapes_stay_named() {
        assertThat(json("a\nb")).contains("\"a\\nb\"");
        assertThat(json("a\tb")).contains("\"a\\tb\"");
        assertThat(json("a\rb")).contains("\"a\\rb\"");
        assertThat(json("a\"b")).contains("\"a\\\"b\"");
        assertThat(json("a\\b")).contains("\"a\\\\b\"");
    }

    @Test
    void an_escaped_surface_reads_back_unchanged() {
        String origin = "train:\u001b[31mred\u001b[0m";
        DynamicSurface surface = surfaceWithOrigin(origin);

        DynamicSurface back = DynamicSurfaceIo.fromJson(DynamicSurfaceIo.toJson(surface));

        assertThat(back.entries()).hasSize(1);
        assertThat(back.entries().getFirst().origin()).isEqualTo(origin);
    }

    private static String json(String origin) {
        return DynamicSurfaceIo.toJson(surfaceWithOrigin(origin));
    }

    private static DynamicSurface surfaceWithOrigin(String origin) {
        return DynamicSurface.of(
                new DynamicSurface.Entry(DynamicSurface.Kind.REFLECTIVE_TYPE, "com.example.Foo", Set.of(), origin));
    }
}
