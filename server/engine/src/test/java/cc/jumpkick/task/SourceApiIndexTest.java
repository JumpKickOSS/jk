// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The declaration baseline classifies a module's edit before any compile: content that moved with
 * its declarations intact is body-only, a moved declaration or a source that appeared or vanished
 * is an API change, and anything the index cannot describe is unknown.
 */
class SourceApiIndexTest {

    private static final String LIB = """
            package p;

            public final class Lib {
                public static int twice(int n) {
                    return n * 2;
                }
            }
            """;

    @Test
    void a_body_edit_is_body_only_and_a_signature_edit_names_the_file(@TempDir Path module) throws IOException {
        Path src = Files.createDirectories(module.resolve("src/p"));
        Path lib = Files.writeString(src.resolve("Lib.java"), LIB);
        Map<String, SourceApiIndex.Row> baseline = SourceApiIndex.of(module, List.of(lib));
        assertThat(baseline).containsOnlyKeys("src/p/Lib.java");

        assertThat(SourceApiIndex.classify(module, baseline, List.of(lib), false)
                        .kind())
                .isEqualTo(SourceApiIndex.Kind.BODY_ONLY);
        Files.writeString(lib, LIB.replace("n * 2", "n + n"));
        assertThat(SourceApiIndex.classify(module, baseline, List.of(lib), false)
                        .kind())
                .isEqualTo(SourceApiIndex.Kind.BODY_ONLY);

        Files.writeString(lib, LIB.replace("int twice", "long twice"));
        SourceApiIndex.Hint moved = SourceApiIndex.classify(module, baseline, List.of(lib), false);
        assertThat(moved.kind()).isEqualTo(SourceApiIndex.Kind.API_CHANGED);
        assertThat(moved.files()).containsExactly("src/p/Lib.java");
    }

    /** A private member is invisible to consumers unless a processor may shape output from it. */
    @Test
    void a_private_member_edit_is_body_only_unless_private_members_matter(@TempDir Path module) throws IOException {
        Path src = Files.createDirectories(module.resolve("src/p"));
        Path lib = Files.writeString(src.resolve("Lib.java"), LIB);
        Map<String, SourceApiIndex.Row> baseline = SourceApiIndex.of(module, List.of(lib));

        Files.writeString(
                lib,
                LIB.replace(
                        "public static int twice",
                        "private static final long STAMP = 1L;\n    public static int twice"));
        assertThat(SourceApiIndex.classify(module, baseline, List.of(lib), false)
                        .kind())
                .as("no processor: nothing can reach a private member")
                .isEqualTo(SourceApiIndex.Kind.BODY_ONLY);
        assertThat(SourceApiIndex.classify(module, baseline, List.of(lib), true).kind())
                .as("a processor may derive API from a private field")
                .isEqualTo(SourceApiIndex.Kind.API_CHANGED);
    }

    @Test
    void a_source_that_appears_or_disappears_is_an_api_change(@TempDir Path module) throws IOException {
        Path src = Files.createDirectories(module.resolve("src/p"));
        Path lib = Files.writeString(src.resolve("Lib.java"), LIB);
        Map<String, SourceApiIndex.Row> baseline = SourceApiIndex.of(module, List.of(lib));

        Path more = Files.writeString(src.resolve("More.java"), "package p; public class More {}");
        assertThat(SourceApiIndex.classify(module, baseline, List.of(lib, more), false)
                        .files())
                .containsExactly("src/p/More.java");
        assertThat(SourceApiIndex.classify(module, baseline, List.of(), false).kind())
                .isEqualTo(SourceApiIndex.Kind.API_CHANGED);
    }

    @Test
    void no_baseline_or_a_source_the_index_does_not_describe_is_unknown(@TempDir Path module) throws IOException {
        Path src = Files.createDirectories(module.resolve("src/p"));
        Path lib = Files.writeString(src.resolve("Lib.java"), LIB);
        assertThat(SourceApiIndex.classify(module, Map.of(), List.of(lib), false))
                .isEqualTo(SourceApiIndex.Hint.UNKNOWN);
        Map<String, SourceApiIndex.Row> baseline = SourceApiIndex.of(module, List.of(lib));
        Path scala = Files.writeString(src.resolve("S.scala"), "object S");
        assertThat(SourceApiIndex.classify(module, baseline, List.of(lib, scala), false))
                .isEqualTo(SourceApiIndex.Hint.UNKNOWN);
    }

    @Test
    void advancing_re_digests_moved_content_and_drops_gone_sources(@TempDir Path module) throws IOException {
        Path src = Files.createDirectories(module.resolve("src/p"));
        Path lib = Files.writeString(src.resolve("Lib.java"), LIB);
        Path gone = Files.writeString(src.resolve("Gone.java"), "package p; class Gone {}");
        Map<String, SourceApiIndex.Row> baseline = SourceApiIndex.of(module, List.of(lib, gone));

        // A restore compiles nothing, yet the source sits at new content: the row follows it.
        Files.writeString(lib, LIB.replace("int twice", "long twice"));
        Map<String, SourceApiIndex.Row> advanced = SourceApiIndex.updated(baseline, module, List.of(), List.of(lib));
        assertThat(advanced).containsOnlyKeys("src/p/Lib.java");
        assertThat(advanced.get("src/p/Lib.java")).isNotEqualTo(baseline.get("src/p/Lib.java"));
        assertThat(Objects.requireNonNull(advanced.get("src/p/Lib.java")).apiToken())
                .isEqualTo(JavaSourceApi.digest(lib));

        Path file = SourceApiIndex.path(module.resolve("target"));
        SourceApiIndex.write(file, advanced);
        assertThat(SourceApiIndex.load(file)).isEqualTo(advanced);
    }
}
