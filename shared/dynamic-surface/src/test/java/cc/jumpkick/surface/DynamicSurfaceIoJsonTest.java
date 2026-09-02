// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.surface;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A golden for the bytes {@code dynamic-surface.json} carries. The module used to hold a private
 * escaper on the grounds that it had to stay dependency-free, and this test existed to pin that
 * copy against {@code Jsonl.quote}; the copy is gone and the module links {@code :host}
 * like everything else, so what is left to pin is the file format itself.
 *
 * <p>The expected forms stay spelled out rather than computed from the owner: a golden that calls
 * the code under test to decide what it should have produced follows a rename of the format and
 * still passes.
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
