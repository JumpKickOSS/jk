// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.image.ImageConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The one JVM-flag hook, read the same way by the entrypoint and the AOT trainer. */
class ImageBuilderJavaOptsTest {

    private static ImageConfig config(Map<String, String> env) {
        return new ImageConfig(
                "eclipse-temurin:25-jre",
                null,
                null,
                List.of(),
                env,
                Map.of(),
                null,
                null,
                List.of(),
                null,
                null,
                null,
                true);
    }

    @Test
    void java_opts_are_split_on_whitespace_and_absent_means_none() {
        assertThat(ImageBuilder.javaOpts(config(Map.of()))).isEmpty();
        assertThat(ImageBuilder.javaOpts(config(Map.of("JAVA_OPTS", "   ")))).isEmpty();
        assertThat(ImageBuilder.javaOpts(
                        config(Map.of("JAVA_OPTS", " -Xmx256m  -XX:+UseSerialGC\t-Dfile.encoding=UTF-8 "))))
                .containsExactly("-Xmx256m", "-XX:+UseSerialGC", "-Dfile.encoding=UTF-8");
        assertThat(ImageBuilder.javaOpts(config(Map.of("LANG", "C.UTF-8"))))
                .as("other env vars are not flags")
                .isEmpty();
    }
}
