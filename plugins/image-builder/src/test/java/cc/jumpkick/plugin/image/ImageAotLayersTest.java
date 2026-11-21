// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.image.ImageConfig;
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer;
import com.google.cloud.tools.jib.api.buildplan.FileEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * With {@code [image] aot-cache = true} the trained tree ships in the layers an image without a
 * cache has, least volatile first, and the cache after them: an application change re-pushes the
 * application's layer and the cache's, never the release jars. Each file keeps the path and time it
 * was trained at, which is what the JVM checks of a classpath entry.
 */
class ImageAotLayersTest {

    @Test
    void the_trained_tree_ships_release_jars_snapshot_jars_and_the_application_as_three_layers(@TempDir Path tmp)
            throws Exception {
        Path store = Files.createDirectories(tmp.resolve("store"));
        Path release = Files.writeString(store.resolve("aa"), "release");
        Path snapshot = Files.writeString(store.resolve("bb"), "snapshot");
        Path staging = Files.createDirectories(tmp.resolve("staging"));
        for (String rel : List.of(
                "classpath/app.jar",
                "libs/lib-1.0.jar",
                "libs/sibling-0.1.0-SNAPSHOT.jar",
                "written-by-training.log")) {
            Path file = staging.resolve(rel);
            Files.createDirectories(file.getParent());
            Files.writeString(file, rel);
        }
        AotCacheTrainer.Result trained = new AotCacheTrainer.Result(
                staging,
                List.of(),
                tmp.resolve("app.aot"),
                Set.of("classpath/app.jar", "libs/lib-1.0.jar", "libs/sibling-0.1.0-SNAPSHOT.jar"));
        ImageBuilder.Plan plan = new ImageBuilder.Plan(
                config(),
                "app",
                "1.0.0",
                "x.Main",
                tmp.resolve("app.jar"),
                List.of(release),
                List.of(snapshot),
                null,
                Map.of(release, "lib-1.0.jar", snapshot, "sibling-0.1.0-SNAPSHOT.jar"),
                null,
                null,
                null);

        List<FileEntriesLayer> layers = ImageBuilder.stagedLayers(trained, plan);

        assertThat(layers)
                .extracting(FileEntriesLayer::getName)
                .containsExactly("dependencies", "snapshot-dependencies", "application");
        assertThat(paths(layers.get(0))).containsExactly("/app/libs/lib-1.0.jar");
        assertThat(paths(layers.get(1))).containsExactly("/app/libs/sibling-0.1.0-SNAPSHOT.jar");
        assertThat(paths(layers.get(2)))
                .as("the application, and nothing the training run wrote")
                .containsExactly("/app/classpath/app.jar");
        for (FileEntriesLayer layer : layers) {
            assertThat(layer.getEntries())
                    .as("every file at the time it was trained at")
                    .allMatch(e -> e.getModificationTime().equals(AotCacheTrainer.LAYER_TIME.toInstant()));
        }
    }

    /** Spring Boot's extractor puts the jars under {@code lib/}, beside its thin launcher jar. */
    @Test
    void a_spring_boot_trees_jars_ship_in_the_dependency_layers(@TempDir Path tmp) throws Exception {
        Path store = Files.createDirectories(tmp.resolve("store"));
        Path release = Files.writeString(store.resolve("aa"), "release");
        Path snapshot = Files.writeString(store.resolve("bb"), "snapshot");
        Path staging = Files.createDirectories(tmp.resolve("staging"));
        for (String rel : List.of("app.jar", "lib/spring-core-6.2.1.jar", "lib/sibling-0.1.0-SNAPSHOT.jar")) {
            Path file = staging.resolve(rel);
            Files.createDirectories(file.getParent());
            Files.writeString(file, rel);
        }
        AotCacheTrainer.Result trained = new AotCacheTrainer.Result(
                staging,
                List.of(),
                tmp.resolve("app.aot"),
                Set.of("app.jar", "lib/spring-core-6.2.1.jar", "lib/sibling-0.1.0-SNAPSHOT.jar"));
        ImageBuilder.Plan plan = new ImageBuilder.Plan(
                config(),
                "app",
                "1.0.0",
                "x.Main",
                tmp.resolve("app.jar"),
                List.of(release),
                List.of(snapshot),
                null,
                Map.of(release, "spring-core-6.2.1.jar", snapshot, "sibling-0.1.0-SNAPSHOT.jar"),
                null,
                null,
                null);

        List<FileEntriesLayer> layers = ImageBuilder.stagedLayers(trained, plan);

        assertThat(layers)
                .extracting(FileEntriesLayer::getName)
                .containsExactly("dependencies", "snapshot-dependencies", "application");
        assertThat(paths(layers.get(0))).containsExactly("/app/lib/spring-core-6.2.1.jar");
        assertThat(paths(layers.get(1))).containsExactly("/app/lib/sibling-0.1.0-SNAPSHOT.jar");
        assertThat(paths(layers.get(2))).containsExactly("/app/app.jar");
    }

    private static List<String> paths(FileEntriesLayer layer) {
        return layer.getEntries().stream()
                .map(FileEntry::getExtractionPath)
                .map(Object::toString)
                .toList();
    }

    private static ImageConfig config() {
        return new ImageConfig(
                "bellsoft/liberica-runtime-container:jre-25-slim-glibc",
                null,
                null,
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                null,
                List.of(),
                null,
                null,
                null,
                true);
    }
}
