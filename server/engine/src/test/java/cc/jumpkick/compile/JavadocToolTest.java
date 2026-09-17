// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JavadocMode;
import cc.jumpkick.testing.ShortTempDirs;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class JavadocToolTest {

    @Test
    void lenient_turns_doclint_off_and_strict_keeps_javadocs_own_checks() {
        List<String> lenient = JavadocTool.options(JavadocMode.LENIENT, 17, List.of());
        assertThat(lenient).contains("-Xdoclint:none", "-notimestamp", "-quiet").containsSequence("--release", "17");
        List<String> strict = JavadocTool.options(JavadocMode.STRICT, 17, List.of());
        assertThat(strict).doesNotContain("-Xdoclint:none").contains("-notimestamp");
        assertThat(JavadocTool.options(JavadocMode.LENIENT, 0, List.of())).doesNotContain("--release");
    }

    /**
     * javadoc reads the module graph the compile did: the module's {@code --add-modules},
     * {@code --add-exports} and {@code --add-reads} ride along, and an export of a system module
     * — which javadoc refuses under {@code --release} as javac does — switches the level to
     * {@code -source}, the one half of the compile's pair javadoc knows.
     */
    @Test
    void the_modules_the_compile_adds_and_exports_ride_along_and_switch_the_level() {
        List<String> javac = List.of(
                "-parameters",
                "--add-modules",
                "jdk.javadoc",
                "--add-exports",
                "jdk.javadoc/jdk.javadoc.internal.tool=ALL-UNNAMED",
                "-Xlint:all");
        List<String> options = JavadocTool.options(JavadocMode.LENIENT, 17, javac);
        assertThat(options)
                .containsSequence("-source", "17")
                .containsSequence("--add-modules", "jdk.javadoc")
                .containsSequence("--add-exports", "jdk.javadoc/jdk.javadoc.internal.tool=ALL-UNNAMED")
                .doesNotContain("--release", "-target", "-parameters", "-Xlint:all", "-Xlint:-options");

        List<String> own = JavadocTool.options(
                JavadocMode.LENIENT, 21, List.of("--add-reads=my.mod=ALL-UNNAMED", "--add-modules=ALL-MODULE-PATH"));
        assertThat(own)
                .containsSequence("--release", "21")
                .contains("--add-reads=my.mod=ALL-UNNAMED", "--add-modules=ALL-MODULE-PATH");
    }

    /** javadoc repeats a comment's problems once per index pass; the results want each once. */
    @Test
    void parse_splits_located_lines_by_severity_and_dedupes() {
        String output = String.join(
                "\n",
                "src/com/example/One.java:6: warning: unknown tag. Unregistered custom tag?",
                " * @custom something",
                "   ^",
                "src/com/example/One.java:12: warning: reference not found: Missing",
                "src/com/example/One.java:6: warning: unknown tag. Unregistered custom tag?",
                "src/com/example/Two.java:3: error: malformed HTML",
                "3 warnings",
                "1 error");
        JavadocTool.Result r = JavadocTool.parse(1, output);
        assertThat(r.success()).isFalse();
        assertThat(r.warnings())
                .containsExactly(
                        "src/com/example/One.java:6: warning: unknown tag. Unregistered custom tag?",
                        "src/com/example/One.java:12: warning: reference not found: Missing");
        assertThat(r.errors()).containsExactly("src/com/example/Two.java:3: error: malformed HTML");
        assertThat(r.output()).isEqualTo(output);
    }

    @Test
    void argfile_quotes_every_argument_and_joins_the_classpath() {
        Path out = ShortTempDirs.path().resolve("out");
        Path source = ShortTempDirs.path().resolve("ws/lib/src/A.java");
        Path depWithSpace = ShortTempDirs.path().resolve("ws/dep a.jar");
        Path classes = ShortTempDirs.path().resolve("ws/classes");

        List<String> lines = JavadocTool.argfileLines(
                out, List.of(source), List.of(depWithSpace, classes), List.of("-quiet", "-Xdoclint:none"));

        assertThat(lines).startsWith("-d", quoted(out), "\"-quiet\"", "\"-Xdoclint:none\"", "-classpath");
        assertThat(lines.get(5)).startsWith("\"" + escaped(depWithSpace)).endsWith(escaped(classes) + "\"");
        assertThat(lines).endsWith(quoted(source));
        assertThat(lines).allSatisfy(l -> assertThat(l).doesNotContain("\n"));
    }

    /** The argfile syntax read back: the value quoted, with a Windows path's backslashes doubled. */
    private static String quoted(Path path) {
        return "\"" + escaped(path) + "\"";
    }

    private static String escaped(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
