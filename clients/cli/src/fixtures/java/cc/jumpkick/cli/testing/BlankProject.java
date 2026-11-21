// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A minimal project for tests about something other than {@code jk new}: a {@code jk.toml} and a
 * {@code Calc} class with its test (plus a {@code Main} for {@code --executable}), written directly,
 * so a build test needs no template catalogue and resolves nothing it does not exercise. Takes the
 * flags the tests have always spelled: {@code --group}/{@code -U}, {@code --name}/{@code -W},
 * {@code --lang}/{@code -l}, {@code --executable}/{@code -e}, {@code --layout}/{@code -L}, and the
 * target directory; {@code --no-module}/{@code -N} is accepted and changes nothing.
 */
public final class BlankProject {

    private BlankProject() {}

    /** Writes the project; returns 0, the exit a scaffold reports, so call sites can assert it. */
    public static int write(String... args) {
        String group = "com.example";
        String name = null;
        String lang = "java";
        boolean executable = false;
        boolean simple = false;
        Path dir = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--group", "-U" -> group = args[++i];
                case "--name", "-W" -> name = args[++i];
                case "--lang", "-l" -> lang = args[++i].toLowerCase(Locale.ROOT);
                case "--executable", "-e" -> executable = true;
                case "--layout", "-L" -> simple = args[++i].equals("simple");
                case "--no-module", "-N" -> {}
                default -> {
                    if (a.startsWith("-")) throw new IllegalArgumentException("BlankProject does not take " + a);
                    dir = Path.of(a);
                }
            }
        }
        if (dir == null) throw new IllegalArgumentException("BlankProject needs a directory");
        String leaf = dir.getFileName() == null ? "app" : dir.getFileName().toString();
        try {
            write(dir, name == null ? leaf : name, group, lang, executable, simple);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return 0;
    }

    private static void write(Path dir, String name, String group, String lang, boolean executable, boolean simple)
            throws IOException {
        Files.createDirectories(dir);
        StringBuilder toml = new StringBuilder()
                .append("name     = \"")
                .append(name)
                .append("\"\n")
                .append("group    = \"")
                .append(group)
                .append("\"\n")
                .append("version  = \"0.1.0\"\n")
                .append("java     = 25\n");
        @Nullable
        String compiler =
                switch (lang) {
                    case "kotlin" -> "kotlin   = \"2.4.21\"\n";
                    case "groovy" -> "groovy   = \"6.0.0\"\n";
                    case "scala" -> "scala    = \"3.10.0\"\n";
                    default -> null;
                };
        if (compiler != null) toml.append(compiler);
        boolean packaged = lang.equals("java") || !simple;
        String main = lang.equals("kotlin") ? "MainKt" : "Main";
        if (executable) {
            toml.append("\n[application]\nmain       = \"")
                    .append(packaged ? group + "." + main : main)
                    .append("\"\n");
        }
        toml.append("\n[format]\nenforce    = true\n");
        Files.writeString(dir.resolve("jk.toml"), toml.toString());

        String ext = lang.equals("kotlin") ? "kt" : lang;
        String pkgPath = packaged ? "/" + group.replace('.', '/') : "";
        Path src = dir.resolve((simple ? "src" : "src/main/" + lang) + pkgPath);
        Path test = dir.resolve((simple ? "test/src" : "src/test/" + lang) + pkgPath);
        Files.createDirectories(src);
        Files.createDirectories(test);
        Files.createDirectories(dir.resolve(simple ? "resources" : "src/main/resources"));
        Files.createDirectories(dir.resolve(simple ? "test/resources" : "src/test/resources"));
        String header = packaged ? "package " + group + (lang.equals("java") ? ";" : "") + "\n\n" : "";
        Files.writeString(src.resolve("Calc." + ext), header + calc(lang));
        Files.writeString(test.resolve("CalcTest." + ext), header + calcTest(lang));
        if (executable) Files.writeString(src.resolve("Main." + ext), header + main(lang));
    }

    private static String calc(String lang) {
        return switch (lang) {
            case "kotlin" -> "class Calc {\n    fun doubleValue(value: Int): Int = value * 2\n}\n";
            case "groovy" -> "class Calc {\n    int doubleValue(int value) {\n        value * 2\n    }\n}\n";
            case "scala" -> "class Calc:\n  def doubleValue(value: Int): Int = value * 2\n";
            default ->
                "public record Calc() {\n    public int doubleValue(int value) {\n        return value * 2;\n    }\n}\n";
        };
    }

    private static String calcTest(String lang) {
        return switch (lang) {
            case "kotlin" -> """
                    import org.junit.jupiter.api.Assertions.assertEquals
                    import org.junit.jupiter.api.Test

                    class CalcTest {
                        @Test
                        fun doubleValueReturnsTwiceTheInput() {
                            assertEquals(10, Calc().doubleValue(5))
                        }
                    }
                    """;
            case "groovy" -> """
                    import org.junit.jupiter.api.Test

                    import static org.junit.jupiter.api.Assertions.assertEquals

                    class CalcTest {
                        @Test
                        void doubleValueReturnsTwiceTheInput() {
                            assertEquals(10, new Calc().doubleValue(5))
                        }
                    }
                    """;
            case "scala" -> """
                    import org.junit.jupiter.api.Assertions.assertEquals
                    import org.junit.jupiter.api.Test

                    class CalcTest:
                      @Test
                      def doubleValueReturnsTwiceTheInput(): Unit =
                        assertEquals(10, Calc().doubleValue(5))
                    """;
            default -> """
                    import static org.junit.jupiter.api.Assertions.assertEquals;

                    import org.junit.jupiter.api.Test;

                    class CalcTest {
                        @Test
                        void doubleValueReturnsTwiceTheInput() {
                            assertEquals(10, new Calc().doubleValue(5));
                        }
                    }
                    """;
        };
    }

    private static String main(String lang) {
        return switch (lang) {
            case "kotlin" -> "fun main() {\n    println(\"Hello, world! 5 * 2 = ${Calc().doubleValue(5)}\")\n}\n";
            case "groovy" ->
                "class Main {\n    static void main(String[] args) {\n        println \"Hello, world! 5 * 2 = ${new Calc().doubleValue(5)}\"\n    }\n}\n";
            case "scala" ->
                "object Main:\n  def main(args: Array[String]): Unit =\n    println(s\"Hello, world! 5 * 2 = ${Calc().doubleValue(5)}\")\n";
            default ->
                "public final class Main {\n    public static void main(String... args) {\n        System.out.println(\"Hello, world! 5 * 2 = \" + new Calc().doubleValue(5));\n    }\n}\n";
        };
    }
}
