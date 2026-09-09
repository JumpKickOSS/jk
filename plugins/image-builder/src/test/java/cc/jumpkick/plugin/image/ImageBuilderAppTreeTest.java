// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.image.ImageConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A packager-produced tree (Quarkus {@code quarkus-app/}) is the whole image application —
 * entrypoint is {@code java -jar quarkus-run.jar}, not a lock-derived classpath + main class.
 */
class ImageBuilderAppTreeTest {

    private static ImageConfig config() {
        return new ImageConfig(
                "bellsoft/liberica-runtime-container:jre-25-slim-glibc",
                null,
                null,
                List.of(),
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

    @Test
    void app_tree_plan_is_recognized_and_entrypoint_is_jar_only(@TempDir Path tmp) throws Exception {
        Path app = tmp.resolve("quarkus-app");
        Files.createDirectories(app.resolve("lib/main"));
        Files.writeString(app.resolve("quarkus-run.jar"), "RUN");
        Files.writeString(app.resolve("lib/main/app.jar"), "APP");
        // A lock-derived dep that must NOT appear in the entrypoint.
        Path dep = tmp.resolve("maven-embedder.jar");
        Files.writeString(dep, "BUILD-TIME");

        ImageBuilder.Plan plan = new ImageBuilder.Plan(
                config(),
                "quark",
                "0.1.0",
                "com.example.Application",
                tmp.resolve("quark-0.1.0.jar"),
                List.of(dep),
                List.of(),
                null,
                Map.of(),
                app,
                "quarkus-run.jar");

        assertThat(plan.hasAppTree()).isTrue();
        assertThat(ImageBuilder.appTreeEntrypoint(plan, false)).containsExactly("java", "-jar", "quarkus-run.jar");
        assertThat(ImageBuilder.appTreeEntrypoint(plan, true))
                .containsExactly("java", "-XX:AOTCache=app.aot", "-jar", "quarkus-run.jar");
        // Entrypoint never names the lock classpath main or build-time deps.
        assertThat(ImageBuilder.appTreeEntrypoint(plan, false))
                .doesNotContain("com.example.Application")
                .doesNotContain("maven-embedder.jar")
                .doesNotContain("-cp");
    }

    @Test
    void app_tree_is_trainable_without_a_framework_exit_hook(@TempDir Path tmp) throws Exception {
        Path app = tmp.resolve("quarkus-app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("quarkus-run.jar"), "RUN");

        ImageBuilder.Plan plan = new ImageBuilder.Plan(
                config(),
                "quark",
                "0.1.0",
                "com.example.Application",
                tmp.resolve("main.jar"),
                List.of(),
                List.of(),
                null,
                Map.of(),
                app,
                "quarkus-run.jar");

        // hasAppTree skips the Boot-only exploded-classes refusal; training uses
        // settle+SIGTERM for any server, not a per-framework exit flag. Hosts without a
        // matching JVM or container runtime are still blocked — that is not a layout refusal.
        String reason = AotCacheTrainer.unsupportedReason(plan);
        if (AotCacheTrainer.containerRuntime(plan.config().dockerExecutable()) != null
                || BaseJre.hostCanExecute(plan.config().platforms())) {
            assertThat(reason).isNull();
        } else {
            assertThat(reason).doesNotContain("exploded-classes").contains("container runtime");
        }
    }
}
