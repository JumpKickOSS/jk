// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.ide;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The generators produce the same bytes they did when they lived in the CLI: {@code ide-golden/}
 * is that output for {@link IdeGoldenFixture}, captured before the move. A deliberate change to a
 * generated file re-captures the golden ({@code IDE_GOLDEN_CAPTURE=<resources dir> ./gradlew
 * :ide:test --tests IdeGoldenFilesTest}); an accidental one fails here.
 *
 * <p>Generated directories whose names start with a dot ({@code .idea}, {@code .vscode}) are stored
 * as {@code dot-idea}, {@code dot-vscode}: the repository ignores dot-directories everywhere, and a
 * golden that git never sees is a test that passes only on the machine that captured it.
 */
class IdeGoldenFilesTest {

    private static final String GOLDEN = "ide-golden/";
    private static final String CAPTURE_ENV = "IDE_GOLDEN_CAPTURE";

    @Test
    void every_generated_file_matches_the_golden_capture(@TempDir Path tmp) throws Exception {
        IdeGoldenFixture.Built built = IdeGoldenFixture.build(tmp.resolve("ws"));
        IdeModel model = IdeModel.fromWire(built.wire(), built.ideConfigDir());

        List<IdeGeneration> generated = IdeGenerators.run(model, EnumSet.allOf(IdeTarget.class), false);

        Map<String, String> actual = IdeGoldenFixture.outputs(built);
        String capture = System.getenv(CAPTURE_ENV);
        if (capture != null && !capture.isBlank()) {
            capture(Path.of(capture), actual);
            return;
        }
        Map<String, String> expected = goldens();
        assertThat(actual.keySet()).containsExactlyElementsOf(expected.keySet());
        for (Map.Entry<String, String> e : expected.entrySet()) {
            assertThat(actual.get(e.getKey())).as(e.getKey()).isEqualTo(e.getValue());
        }

        // The reported file list is the written set, and the SDK tables are reported apart from it.
        List<String> reported = new ArrayList<>();
        List<String> tables = new ArrayList<>();
        for (IdeGeneration g : generated) {
            for (Path p : g.files()) reported.add(rel(built.ws(), p));
            for (Path p : g.sdkTables()) tables.add(rel(built.ws(), p));
        }
        List<String> projectFiles = new ArrayList<>(expected.keySet());
        projectFiles.removeIf(f -> f.startsWith("ide-config/"));
        assertThat(reported).containsExactlyInAnyOrderElementsOf(projectFiles);
        assertThat(tables)
                .containsExactlyInAnyOrder(
                        "ide-config/Google/AndroidStudio2025.1/options/jdk.table.xml",
                        "ide-config/JetBrains/IntelliJIdea2025.1/options/jdk.table.xml");
    }

    @Test
    void preview_lists_the_same_files_and_writes_nothing(@TempDir Path tmp) throws Exception {
        IdeGoldenFixture.Built built = IdeGoldenFixture.build(tmp.resolve("ws"));
        IdeModel model = IdeModel.fromWire(built.wire(), built.ideConfigDir());

        List<IdeGeneration> preview = IdeGenerators.run(model, EnumSet.allOf(IdeTarget.class), true);

        assertThat(IdeGoldenFixture.outputs(built)).isEmpty();
        List<String> listed = new ArrayList<>();
        for (IdeGeneration g : preview) {
            assertThat(g.sdkTables()).isEmpty();
            for (Path p : g.files()) listed.add(rel(built.ws(), p));
        }
        List<String> projectFiles = new ArrayList<>(goldens().keySet());
        projectFiles.removeIf(f -> f.startsWith("ide-config/"));
        assertThat(listed).containsExactlyInAnyOrderElementsOf(projectFiles);
    }

    private static Map<String, String> goldens() throws IOException {
        Map<String, String> out = new TreeMap<>();
        for (String line : resource(GOLDEN + "index.txt").split("\n")) {
            if (line.isBlank()) continue;
            out.put(line, resource(GOLDEN + stored(line)));
        }
        return out;
    }

    /** Where a generated file's golden lives: a leading dot-directory is spelled {@code dot-}. */
    private static String stored(String generated) {
        return generated.startsWith(".") ? "dot-" + generated.substring(1) : generated;
    }

    /** Re-capture: write every generated file and the index under {@code resources}, then stop. */
    private static void capture(Path resources, Map<String, String> actual) throws IOException {
        Path root = resources.resolve(GOLDEN);
        for (Map.Entry<String, String> e : actual.entrySet()) {
            Path target = root.resolve(stored(e.getKey()));
            Files.createDirectories(target.getParent());
            Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
        }
        Files.writeString(root.resolve("index.txt"), String.join("\n", actual.keySet()) + "\n", StandardCharsets.UTF_8);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = IdeGoldenFilesTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IOException("missing test resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String rel(Path ws, Path p) {
        return ws.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
