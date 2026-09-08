// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.guard.api.Blank;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextViewTest {

    @TempDir
    Path root;

    @BeforeEach
    void tree() throws Exception {
        write("src/main/java/a/B.java", "package a; // top\nclass B { String s = \"lit\"; }\n");
        write("src/main/java/a/C.kt", "val x = \"k\"\n");
        write("src/test/java/a/BTest.java", "class BTest {}\n");
        write("src/main/java/build/Gen.java", "class Gen {}\n"); // a package named build: source
        write("build.gradle.kts", "plugins { java }\n");
        write("build/generated/G.java", "class G {}\n"); // Gradle's build/, beside its script: output
        write("src/main/java/node_modules/x.js", "x\n");
        write("src/main/java/.hidden/H.java", "class H {}\n");
        write(".github/workflows/ci.yml", "on: push\n");
        write(".jk/after-build.kts", "println(1)\n");
        write(".idea/misc.xml", "<x/>\n");
        write("target/guard/report.jsonl", "{}\n");
        write("docs/README.md", "# hi\n");
    }

    private void write(String rel, String text) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    @Test
    void files_are_root_relative_sorted_and_matched_by_glob() {
        TextView t = new TextView(root, List.of(root.resolve("src")));
        assertThat(t.files("**/*.java"))
                .containsExactly(
                        "src/main/java/a/B.java", "src/main/java/build/Gen.java", "src/test/java/a/BTest.java");
        assertThat(t.files("src/main/**/*.kt")).containsExactly("src/main/java/a/C.kt");
        assertThat(t.files("*.java"))
                .as("a single star does not cross directories")
                .isEmpty();
        assertThat(t.files("src/main/java/a/?.java")).containsExactly("src/main/java/a/B.java");
    }

    @Test
    void node_modules_and_dot_directories_are_not_the_source_tree_but_a_package_named_build_is() {
        TextView t = new TextView(root, List.of(Path.of("src")));
        List<String> all = t.files("**");
        assertThat(all).doesNotContain("src/main/java/node_modules/x.js", "src/main/java/.hidden/H.java");
        assertThat(all).contains("src/main/java/a/B.java", "src/main/java/build/Gen.java");
    }

    @Test
    void gradles_build_directory_is_known_by_the_script_beside_it() {
        TextView t = new TextView(root, List.of(Path.of("")), false, "target");
        List<String> all = t.files("**");
        assertThat(all).doesNotContain("build/generated/G.java");
        assertThat(all).contains("build.gradle.kts", "src/main/java/build/Gen.java");
    }

    @Test
    void the_repository_s_own_dot_directories_are_text_but_the_output_tree_is_not() {
        TextView t = new TextView(root, List.of(Path.of("")), false, "target");
        List<String> all = t.files("**");
        assertThat(all).contains(".github/workflows/ci.yml", ".jk/after-build.kts", "docs/README.md");
        assertThat(all).doesNotContain(".idea/misc.xml", "target/guard/report.jsonl");
        assertThat(t.skipped("target")).isTrue();
        assertThat(t.skipped("target/x")).isTrue();
        assertThat(t.skipped("targets/x")).as("a prefix is not the directory").isFalse();
        assertThat(t.skipped(".github/x")).isFalse();
        assertThat(t.skipped("a/.git/x")).isTrue();
    }

    @Test
    void a_fixture_run_matches_globs_against_file_names_only() {
        TextView t = new TextView(root, List.of(Path.of("src")), true, null);
        assertThat(t.files("**/src/main/java/**/*.java"))
                .as("the fixture has no tree shape; the name pattern *.java is what matches")
                .containsExactly(
                        "src/main/java/a/B.java", "src/main/java/build/Gen.java", "src/test/java/a/BTest.java");
        assertThat(t.files("x/y/B.java")).containsExactly("src/main/java/a/B.java");
    }

    @Test
    void a_missing_source_root_contributes_nothing() {
        TextView t = new TextView(root, List.of(Path.of("nope"), Path.of("docs")));
        assertThat(t.files("**")).containsExactly("docs/README.md");
    }

    @Test
    void blanked_views_lines_and_literals_read_the_same_file() {
        TextView t = new TextView(root, List.of(Path.of("src")));
        String raw = t.blanked("src/main/java/a/B.java", Blank.NONE);
        assertThat(raw).contains("// top");
        assertThat(t.blanked("src/main/java/a/B.java", Blank.COMMENTS))
                .doesNotContain("// top")
                .contains("\"lit\"");
        assertThat(t.blanked("src/main/java/a/B.java", Blank.COMMENTS_AND_STRINGS))
                .contains("\"   \"");
        assertThat(t.blanked("src/main/java/a/B.java", Blank.CODE))
                .contains("// top")
                .doesNotContain("class B");
        assertThat(t.lines("src/main/java/a/B.java"))
                .containsExactly("package a; // top", "class B { String s = \"lit\"; }");
        assertThat(t.literals("src/main/java/a/B.java")).containsExactly("lit");
    }

    @Test
    void a_view_is_computed_once_per_mode_and_file() {
        TextView t = new TextView(root, List.of(Path.of("src")));
        String first = t.blanked("src/main/java/a/B.java", Blank.COMMENTS);
        assertThat(t.blanked("src/main/java/a/B.java", Blank.COMMENTS)).isSameAs(first);
        assertThat(t.files("**/*.kt")).isSameAs(t.files("**/*.kt"));
    }

    @Test
    void an_unreadable_path_names_the_file_and_the_root() {
        TextView t = new TextView(root, List.of(Path.of("src")));
        assertThatThrownBy(() -> t.lines("src/main/java/a/Missing.java"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("src/main/java/a/Missing.java")
                .hasMessageContaining(root.toAbsolutePath().normalize().toString());
    }

    @Test
    void glob_patterns_translate_the_three_wildcards() {
        assertThat(TextView.globPattern("**").matcher("a/b/c").matches()).isTrue();
        assertThat(TextView.globPattern("a/**/c").matcher("a/c").matches())
                .as("** may match nothing")
                .isTrue();
        assertThat(TextView.globPattern("a/**/c").matcher("a/x/y/c").matches()).isTrue();
        assertThat(TextView.globPattern("a/*/c").matcher("a/x/y/c").matches()).isFalse();
        assertThat(TextView.globPattern("a/?.java").matcher("a/B.java").matches())
                .isTrue();
        assertThat(TextView.globPattern("a/?.java").matcher("a/BB.java").matches())
                .isFalse();
        assertThat(TextView.globPattern("a.b").matcher("aXb").matches())
                .as("a dot is literal")
                .isFalse();
    }
}
