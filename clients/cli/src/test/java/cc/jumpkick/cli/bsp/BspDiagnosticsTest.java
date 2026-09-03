// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code publishDiagnostics} placement. Parsing is {@code CompilerLocus}, the one header
 * vocabulary the CLI, MCP, journal and engine share: multi-line javac blocks, groovyc's spaced
 * header, and Windows paths publish at the header file/line, not project root line 0.
 */
class BspDiagnosticsTest {

    private static final Path PROJECT = Path.of("/w/proj");

    private static Map<String, List<String>> collect(int severity, String... blocks) {
        Map<String, List<String>> byFile = new LinkedHashMap<>();
        BspServer.collectDiagnostics(byFile, new ArrayList<>(List.of(blocks)), severity, PROJECT);
        return byFile;
    }

    @Test
    void a_multi_line_javac_block_publishes_at_header_file_line_and_caret_column() {
        String block = "/w/proj/src/main/java/demo/App.java:12: error: cannot find symbol\n"
                + "        LibTestHelper.expectedTwice(2);\n"
                + "        ^\n"
                + "  symbol:   class LibTestHelper";
        Map<String, List<String>> byFile = collect(1, block);

        assertThat(byFile.keySet()).singleElement().asString().endsWith("App.java");
        String diag = byFile.values().iterator().next().getFirst();
        assertThat(diag).contains("\"line\":11");
        assertThat(diag).contains("\"character\":8");
        assertThat(diag).contains("\"severity\":1");
        assertThat(diag).contains("cannot find symbol");
        assertThat(diag).contains("symbol:   class LibTestHelper");
    }

    @Test
    void a_groovyc_block_reads_the_column_trailer() {
        String block = "/w/proj/src/Foo.groovy: 5: unexpected token: } @ line 5, column 1.\n" + "   }\n" + "   ^";
        Map<String, List<String>> byFile = collect(1, block);

        assertThat(byFile.keySet()).singleElement().asString().endsWith("Foo.groovy");
        String diag = byFile.values().iterator().next().getFirst();
        assertThat(diag).contains("\"line\":4");
        assertThat(diag).contains("\"character\":0");
    }

    @Test
    void a_kotlinc_line_publishes_file_line_and_column() {
        Map<String, List<String>> byFile = collect(1, "/w/proj/src/Main.kt:3:15: error: unresolved reference: foo");
        assertThat(byFile.keySet()).singleElement().asString().endsWith("Main.kt");
        String diag = byFile.values().iterator().next().getFirst();
        assertThat(diag).contains("\"line\":2");
        assertThat(diag).contains("\"character\":14");
    }

    @Test
    void a_windows_drive_path_is_a_file_not_project_root() {
        Map<String, List<String>> byFile = collect(1, "C:\\src\\Foo.java:7: error: ';' expected");
        assertThat(byFile.keySet()).singleElement().asString().contains("Foo.java");
        assertThat(byFile.values().iterator().next().getFirst()).contains("\"line\":6");
    }

    @Test
    void warnings_publish_with_severity_two() {
        Map<String, List<String>> byFile =
                collect(2, "/w/proj/src/main/java/demo/App.java:4: warning: [deprecation] old() is deprecated");
        assertThat(byFile.values().iterator().next().getFirst()).contains("\"severity\":2");
    }

    @Test
    void an_unparseable_block_lands_at_project_root_line_zero() {
        Map<String, List<String>> byFile = collect(1, "worker crashed before compiling anything");
        assertThat(byFile.keySet()).singleElement().asString().contains("proj");
        String diag = byFile.values().iterator().next().getFirst();
        assertThat(diag).contains("\"line\":0");
        assertThat(diag).contains("\"severity\":1");
    }
}
