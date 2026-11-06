// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.GraphCacheKeys;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A cached fragment is used as long as its file's bytes (or its compile's keys) and {@link
 * GraphCacheKeys#VERSION} match: a change to what makes a fragment, without a new version, leaves every
 * checkout answering from the old reading. These are the sources a fragment and compiled facts come
 * from, and the mapping of each compiled class to its source, which a cached class set holds; this
 * test holds their digest beside the version that produced it, so changing them fails here until the
 * version moves and the new digest is recorded.
 */
class ExtractorVersionTest {

    /** Relative to this module, where its tests run. */
    private static final List<Path> SOURCES = List.of(
            Path.of("../../shared/code-graph/src/main/java/cc/jumpkick/codegraph/extract"),
            Path.of("src/main/java/cc/jumpkick/graph/JvmFacts.java"),
            // Which source a compiled class maps to, and the module and source set tables it reads.
            Path.of("src/main/java/cc/jumpkick/graph/ClassSources.java"),
            Path.of("../../shared/code-graph/src/main/java/cc/jumpkick/codegraph/Modules.java"),
            Path.of("../../server/guard/src/main/java/cc/jumpkick/guard/extract/FactsExtractor.java"));

    private static final Path RECORDED = Path.of("src/test/resources/extractor-sources.txt");

    @Test
    void the_sources_a_cached_fragment_comes_from_change_only_with_the_extractor_version() throws IOException {
        String recorded = Files.readString(RECORDED, StandardCharsets.UTF_8).strip();
        String now = "version " + GraphCacheKeys.VERSION + " sha256 " + digest();
        assertThat(now)
                .as(
                        "write \"%s\" to %s: a source a cached graph fragment comes from changed, and if what it"
                                + " produces changed, GraphCacheKeys.VERSION moves too",
                        now, RECORDED)
                .isEqualTo(recorded);
    }

    /**
     * The digest covers the mapping only while the mapping stays in the files it reads: a class set's
     * facts mapped to sources anywhere else would change cached fragments unseen.
     */
    @Test
    void compiled_facts_are_mapped_to_sources_only_in_a_digested_file() throws IOException {
        List<Path> mapping = new ArrayList<>();
        PathUtil.forEachRegularFile(Path.of("src/main/java"), (file, attrs) -> {
            if (Files.readString(file, StandardCharsets.UTF_8).contains("JvmFacts.map(")) mapping.add(file);
        });
        assertThat(mapping)
                .as("JvmFacts.map is called from ClassSources alone")
                .containsExactly(Path.of("src/main/java/cc/jumpkick/graph/ClassSources.java"));
    }

    private static String digest() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path source : SOURCES) {
            if (Files.isRegularFile(source)) {
                files.add(source);
                continue;
            }
            PathUtil.forEachRegularFile(source, (file, attrs) -> {
                if (file.getFileName().toString().endsWith(".java")) files.add(file);
            });
        }
        // Ordered by the '/' form as a String: Windows paths compare case-insensitively.
        files.sort(Comparator.comparing(ExtractorVersionTest::slashed));
        StringBuilder all = new StringBuilder();
        for (Path file : files) {
            // Line endings as a checkout writes them are not a change to what the code does.
            String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            all.append(slashed(file)).append('\n').append(text);
        }
        return Hashing.sha256Hex(all.toString());
    }

    private static String slashed(Path file) {
        return file.toString().replace('\\', '/');
    }
}
