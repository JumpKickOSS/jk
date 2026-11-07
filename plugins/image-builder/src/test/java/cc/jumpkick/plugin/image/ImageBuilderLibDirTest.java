// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.image.ImageConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The lib-dir layout ships as itself: the runnable jar at {@code /app}, started with {@code java
 * -jar} so its {@code Class-Path} finds the lib directory beside it.
 */
class ImageBuilderLibDirTest {

    private static ImageConfig config(Map<String, String> env) {
        return new ImageConfig(
                "bellsoft/liberica-runtime-container:jre-25-slim-glibc",
                null,
                null,
                List.of(),
                env,
                Map.of(),
                Map.of(),
                null,
                null,
                List.of(),
                null,
                null,
                null,
                false);
    }

    private static ImageBuilder.Plan plan(ImageConfig config, @Nullable String libDir) {
        return new ImageBuilder.Plan(
                config,
                "helidon-quickstart-se",
                "1.0.0",
                "io.helidon.examples.quickstart.se.Main",
                Path.of("/w/target/helidon-quickstart-se.jar"),
                List.of(Path.of("/store/aa")),
                List.of(),
                null,
                Map.of(Path.of("/store/aa"), "helidon-webserver-27.0.1.jar"),
                null,
                null,
                libDir);
    }

    @Test
    void the_entrypoint_runs_the_layout_jar_with_the_java_opts_first() {
        ImageBuilder.Plan plan = plan(config(Map.of("JAVA_OPTS", "-Xmx256m -XX:+UseZGC")), "libs");

        assertThat(plan.hasLibDir()).isTrue();
        assertThat(plan.hasAppTree()).isFalse();
        assertThat(ImageBuilder.libDirEntrypoint(plan))
                .containsExactly("java", "-Xmx256m", "-XX:+UseZGC", "-jar", "helidon-quickstart-se.jar");
    }

    @Test
    void no_lib_dir_is_the_classic_layout() {
        assertThat(plan(config(Map.of()), null).hasLibDir()).isFalse();
        assertThat(plan(config(Map.of()), " ").hasLibDir()).isFalse();
    }
}
