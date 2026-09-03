// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.image.ImageConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The OCI tarball is a function of its base image and of the worker that built it. Both are
 * identified by content — a resolved digest and a worker-jar hash — so a republished base or a
 * rebuilt worker cannot reuse a prior cache entry.
 */
class ImageActionKeyTest {

    private static final String CONFIG_BASE = "eclipse-temurin:25-jre";

    private static ImageConfig config() {
        return new ImageConfig(
                CONFIG_BASE, null, List.of(), Map.of(), Map.of(), null, null, List.of(), null, null, null, false);
    }

    private static String token(List<String> tokens, String prefix) {
        return tokens.stream()
                .filter(t -> t.startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + prefix + " token in " + tokens));
    }

    private List<String> tokens(Path mainJar, String base, Path workerJar) throws IOException {
        return ImagePlans.imageTokens(
                mainJar, List.of(), List.of(), null, "com.example.Main", base, config(), "", workerJar);
    }

    /**
     * Two different resolved bases, everything else identical: the keys must differ. A tag string
     * such as {@code cfg:…base=eclipse-temurin:25-jre} is the same on both sides of an upstream
     * republish; the resolved digest is not.
     */
    @Test
    void the_resolved_base_digest_is_in_the_key(@TempDir Path tmp) throws Exception {
        Path mainJar = Files.writeString(tmp.resolve("app.jar"), "MAIN");
        Path worker = Files.writeString(tmp.resolve("jk-image-builder.jar"), "WORKER");

        List<String> before = tokens(mainJar, CONFIG_BASE + "@sha256:" + "a".repeat(64), worker);
        List<String> after = tokens(mainJar, CONFIG_BASE + "@sha256:" + "b".repeat(64), worker);

        assertThat(token(before, "base:")).contains("a".repeat(64));
        assertThat(before)
                .as("a republished base image must not hit the cache entry built on the old layers")
                .isNotEqualTo(after);
    }

    /**
     * The worker jar is hashed by content, matching the documented contract in
     * {@code docs/contributors/plugins.md}: rebuilding the plugin invalidates its cached output.
     */
    @Test
    void the_worker_is_identified_by_jar_content(@TempDir Path tmp) throws Exception {
        Path mainJar = Files.writeString(tmp.resolve("app.jar"), "MAIN");
        Path worker = Files.writeString(tmp.resolve("jk-image-builder.jar"), "WORKER v1");
        String base = CONFIG_BASE + "@sha256:" + "a".repeat(64);

        String v1 = token(tokens(mainJar, base, worker), "worker:");
        Files.writeString(worker, "WORKER v2");
        String v2 = token(tokens(mainJar, base, worker), "worker:");

        assertThat(v1).isNotEqualTo(v2);
        assertThat(v1)
                .as("neither the artifact id nor the release version moves when the worker is rebuilt")
                .doesNotContain("jk-image-builder");
    }

    /** Identical inputs, identical tokens — the cache has to hit when nothing moved. */
    @Test
    void identical_inputs_produce_identical_tokens(@TempDir Path tmp) throws Exception {
        Path mainJar = Files.writeString(tmp.resolve("app.jar"), "MAIN");
        Path worker = Files.writeString(tmp.resolve("jk-image-builder.jar"), "WORKER");
        String base = CONFIG_BASE + "@sha256:" + "a".repeat(64);

        assertThat(tokens(mainJar, base, worker)).isEqualTo(tokens(mainJar, base, worker));
    }
}
