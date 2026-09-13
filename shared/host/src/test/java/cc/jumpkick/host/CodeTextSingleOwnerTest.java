// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The comment/string blanker has one owner in production code: {@link CodeText}. A second copy
 * inside a module is what this catches — it is the shape that produced three.
 */
class CodeTextSingleOwnerTest {

    /** A method declaration named like the lexer, in Java or Kotlin. */
    private static final Pattern LEXER_DECL = Pattern.compile(
            "(?m)^\\s*(?:(?:public|private|static|final|\\s)*\\S+\\s+|fun\\s+)(blankNonCode|javaCodeOnly|blankComments)\\s*\\(");

    @Test
    void only_CodeText_declares_a_blanker() throws IOException {
        Path root = RepoRoot.find(CodeTextSingleOwnerTest.class);
        List<String> owners = new ArrayList<>();
        int scanned = 0;
        for (String family : List.of("shared", "server", "clients", "plugins")) {
            Path dir = root.resolve(family);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> modules = Files.list(dir)) {
                for (Path module : modules.toList()) {
                    Path main = module.resolve("src/main/java");
                    if (!Files.isDirectory(main)) continue;
                    try (Stream<Path> files = Files.walk(main)) {
                        for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                                .toList()) {
                            scanned++;
                            if (LEXER_DECL
                                    .matcher(Files.readString(f, StandardCharsets.UTF_8))
                                    .find()) {
                                owners.add(root.relativize(f).toString().replace('\\', '/'));
                            }
                        }
                    }
                }
            }
        }
        assertThat(scanned)
                .as("main sources scanned — zero means the walk lost the tree")
                .isGreaterThan(1000);
        assertThat(owners)
                .as("a second comment/string lexer in production code; use CodeText")
                .isEmpty();
    }
}
