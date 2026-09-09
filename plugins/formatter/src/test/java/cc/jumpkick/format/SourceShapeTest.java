// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The post-mortem a timed-out file gets: the nesting shape that explains a formatter's line-break
 * search blowing up, and silence when the file's shape explains nothing.
 */
class SourceShapeTest {

    @TempDir
    Path dir;

    /** The shape that pegged a formatter for minutes: providers built from nested {@code flatMap}s. */
    private static final String NESTED_LAMBDAS = """
            class Providers {
                Arbitrary<Lockfile> lockfiles() {
                    return names().flatMap(name -> versions().flatMap(version -> hashes().flatMap(hash ->
                        sources().flatMap(source -> scopes().flatMap(scope -> classifiers().flatMap(classifier ->
                            Arbitraries.of(new Package(name, version, hash, source, scope, classifier))))))));
                }
            }
            """;

    @Test
    void deep_paren_nesting_is_measured_with_the_line_it_peaks_on() {
        SourceShape.Shape shape = SourceShape.of(NESTED_LAMBDAS);

        assertThat(shape.parenDepth()).isGreaterThanOrEqualTo(8);
        assertThat(shape.parenLine()).isEqualTo(5);
        assertThat(shape.lambdaNesting()).isEqualTo(6);
    }

    @Test
    void ordinary_source_is_shallow() {
        SourceShape.Shape shape = SourceShape.of("""
                class Ordinary {
                    int add(int a, int b) {
                        return Math.max(0, a + b);
                    }
                }
                """);

        assertThat(shape.parenDepth()).isEqualTo(1);
        assertThat(shape.lambdaNesting()).isZero();
    }

    @Test
    void parentheses_in_comments_and_string_literals_are_not_structure() {
        SourceShape.Shape shape = SourceShape.of("""
                class Quoted {
                    // ((((((((((((
                    String s = "((((((((((((";
                }
                """);

        assertThat(shape.parenDepth()).isZero();
    }

    @Test
    void the_post_mortem_names_the_nesting_and_the_line() throws Exception {
        File file = write("Providers.java", NESTED_LAMBDAS);

        assertThat(SourceShape.postMortem(file))
                .contains("deepest expression nesting here is")
                .contains("at line 5")
                .contains("6 nested lambdas")
                .contains("named locals or helper methods");
    }

    @Test
    void an_unremarkable_file_gets_no_post_mortem() throws Exception {
        File file = write("Ordinary.java", "class Ordinary {\n    int a = 1;\n}\n");

        assertThat(SourceShape.postMortem(file)).isEmpty();
    }

    @Test
    void an_unreadable_file_gets_no_post_mortem() {
        assertThat(SourceShape.postMortem(dir.resolve("Missing.java").toFile())).isEmpty();
    }

    private File write(String name, String source) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, source, StandardCharsets.UTF_8);
        return p.toFile();
    }
}
