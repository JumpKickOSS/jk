// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourcePathsTest {

    private static void file(Path root, String rel) throws IOException {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "");
    }

    @Test
    void a_site_is_spelled_under_the_root_that_holds_it(@TempDir Path ws) throws IOException {
        Path m = ws.resolve("m");
        file(m, "src/main/kotlin/a/K.kt");
        file(m, "src/main/java/a/J.java");
        file(m, "src/test/kotlin/a/T.kt");
        file(m, "src/integration/java/a/I.java");
        assertThat(SourcePaths.resolve("m", m, "a/K.kt")).isEqualTo("m/src/main/kotlin/a/K.kt");
        assertThat(SourcePaths.resolve("m", m, "a/J.java")).isEqualTo("m/src/main/java/a/J.java");
        assertThat(SourcePaths.resolve("m", m, "a/T.kt")).isEqualTo("m/src/test/kotlin/a/T.kt");
        assertThat(SourcePaths.resolve("m", m, "a/I.java")).isEqualTo("m/src/integration/java/a/I.java");
        assertThat(SourcePaths.resolve("m", m, "a/Gone.java"))
                .as("no root holds it: the layout every reader expects")
                .isEqualTo("m/src/main/java/a/Gone.java");
        assertThat(SourcePaths.resolve("m", null, "a/K.kt")).isEqualTo("m/src/main/java/a/K.kt");
    }

    @Test
    void the_root_module_and_a_compact_module_name_their_real_roots(@TempDir Path ws) throws IOException {
        file(ws, "src/main/java/a/R.java");
        assertThat(SourcePaths.resolve("", ws, "a/R.java")).isEqualTo("src/main/java/a/R.java");
        Path c = ws.resolve("c");
        file(c, "src/a/C.java");
        Files.writeString(c.resolve("jk.toml"), "name = \"c\"\nlayout = \"simple\"\n");
        assertThat(SourcePaths.resolve("c", c, "a/C.java")).isEqualTo("c/src/a/C.java");
        assertThat(SourcePaths.rootHolding(c, "a/C.java")).isEqualTo("src");
        assertThat(SourcePaths.rootHolding(c, "a/Nope.java")).isNull();
    }

    /** The user page states the spelling an `allow` path must follow, with a Kotlin-root and a compact example. */
    @Test
    void the_guards_page_states_the_spelling_rule_with_both_examples() throws IOException {
        String page = Files.readString(RepoRoot.file(SourcePathsTest.class, "docs/user/guards.md"));
        assertThat(page)
                .contains("`" + SourcePaths.FALLBACK + "` is the spelling only when no")
                .containsPattern("in\\s*=\\s*\"\\w+/src/main/kotlin/[\\w/]+\\.kt\"")
                .containsPattern("in\\s*=\\s*\"\\w+/src/[\\w/]+\\.java\"")
                .contains("@Allow(in = …)");
    }
}
