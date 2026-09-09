// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ImageConfigTest {

    private static ImageConfig config(@Nullable String name, @Nullable String registry, @Nullable String tag) {
        return new ImageConfig(
                "eclipse-temurin:25-jre",
                name,
                null,
                List.of(),
                Map.of(),
                Map.of(),
                registry,
                tag,
                List.of(),
                null,
                null,
                null,
                false);
    }

    /** A workspace module is `app`; the image it ships as is whatever `[image] name` says. */
    @Test
    void the_image_name_overrides_the_artifact_id_and_the_tag_overrides_the_version() {
        assertThat(config(null, null, null).targetReference("app", "0.1.0")).isEqualTo("app:0.1.0");
        assertThat(config("gyggly-app", null, null).targetReference("app", "0.1.0"))
                .isEqualTo("gyggly-app:0.1.0");
        assertThat(config("gyggly-app", "ghcr.io/acme", "edge").targetReference("app", "0.1.0"))
                .isEqualTo("ghcr.io/acme/gyggly-app:edge");
        assertThat(config("  ", null, null).targetReference("app", "0.1.0"))
                .as("a blank name is no name")
                .isEqualTo("app:0.1.0");
    }

    /** The parts the engine reports separately resolve exactly as the reference does. */
    @Test
    void repository_and_tag_resolve_the_same_way_the_reference_does() {
        ImageConfig named = config("gyggly-app", null, "edge");
        assertThat(named.repository("app")).isEqualTo("gyggly-app");
        assertThat(named.tagOr("0.1.0")).isEqualTo("edge");
        ImageConfig bare = config(null, null, null);
        assertThat(bare.repository("app")).isEqualTo("app");
        assertThat(bare.tagOr("0.1.0")).isEqualTo("0.1.0");
    }
}
