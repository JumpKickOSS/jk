// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The identity a run-tests stamp keys the code that judges a suite by: the classes of the
 * orchestrator package and nothing else of the jar they sit in, so an engine edit elsewhere keeps
 * it and an edit to the package moves it.
 */
class OrchestratorCodeTest {

    private static final String JUDGE = OrchestratorCode.PACKAGE + "ResultAggregator.class";
    private static final String ENGINE = "cc/jumpkick/runtime/TestSteps.class";

    @Test
    void an_engine_edit_outside_the_package_keeps_the_digest(@TempDir Path tmp) throws IOException {
        String one = OrchestratorCode.of(jar(tmp.resolve("one.jar"), Map.of(JUDGE, "judge", ENGINE, "engine 1")));
        String two = OrchestratorCode.of(jar(tmp.resolve("two.jar"), Map.of(JUDGE, "judge", ENGINE, "engine 2")));
        assertThat(two).isEqualTo(one).hasSize(12);
    }

    @Test
    void an_edit_inside_the_package_moves_the_digest(@TempDir Path tmp) throws IOException {
        String one = OrchestratorCode.of(jar(tmp.resolve("one.jar"), Map.of(JUDGE, "judge 1", ENGINE, "engine")));
        String two = OrchestratorCode.of(jar(tmp.resolve("two.jar"), Map.of(JUDGE, "judge 2", ENGINE, "engine")));
        assertThat(two).isNotEqualTo(one);
        String added = OrchestratorCode.of(jar(
                tmp.resolve("three.jar"),
                Map.of(JUDGE, "judge 1", OrchestratorCode.PACKAGE + "RunShortfall.class", "rule", ENGINE, "engine")));
        assertThat(added).as("a class added to the package").isNotEqualTo(one);
    }

    @Test
    void a_classes_directory_is_read_by_content(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("classes");
        write(dir, JUDGE, "judge 1");
        write(dir, ENGINE, "engine 1");
        String one = OrchestratorCode.of(dir);
        write(dir, ENGINE, "engine 2");
        assertThat(OrchestratorCode.of(dir)).isEqualTo(one);
        write(dir, JUDGE, "judge 2");
        assertThat(OrchestratorCode.of(dir)).isNotEqualTo(one);
    }

    @Test
    void a_location_without_the_package_is_refused(@TempDir Path tmp) throws IOException {
        Path jar = jar(tmp.resolve("engine.jar"), Map.of(ENGINE, "engine"));
        assertThatThrownBy(() -> OrchestratorCode.of(jar)).isInstanceOf(IOException.class);
    }

    @Test
    void the_running_classes_have_a_digest() {
        assertThat(OrchestratorCode.digest()).hasSize(12).doesNotStartWith("engine-");
    }

    /**
     * The digest reads one package, so the module must hold no class outside it: one would change
     * how a suite is judged with no stamp moving.
     */
    @Test
    void every_class_of_the_module_is_in_the_package() throws Exception {
        Path location = Objects.requireNonNull(OrchestratorCode.location());
        List<String> classes = new ArrayList<>();
        if (Files.isDirectory(location)) {
            PathUtil.forEachRegularFile(
                    location,
                    (file, attrs) ->
                            classes.add(location.relativize(file).toString().replace('\\', '/')));
        } else {
            try (ZipFile zip = new ZipFile(location.toFile())) {
                zip.stream().map(ZipEntry::getName).forEach(classes::add);
            }
        }
        List<String> compiled =
                classes.stream().filter(n -> n.endsWith(".class")).toList();
        assertThat(compiled).isNotEmpty().allMatch(n -> n.startsWith(OrchestratorCode.PACKAGE));
    }

    private static Path jar(Path jar, Map<String, String> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : new TreeMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar;
    }

    private static void write(Path dir, String entry, String text) throws IOException {
        Path file = dir.resolve(entry);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
