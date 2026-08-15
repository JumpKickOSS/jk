// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.TestAnsi;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * — hidden redirect stubs / aliases for the pre-split spellings: {@code jk cache info}
 * and {@code jk cache storage} (now {@code jk cache usage}) and {@code jk cache search} (now
 * {@code jk repo search}). Aliases keep working, stay out of {@code jk cache --help} when hidden,
 * and are listed in docs/aliases.md.
 */
class CacheRedirectStubTest {

    @Test
    void cache_info_is_an_alias_of_cache_usage(@TempDir Path tempDir) {
        Path cache = tempDir.resolve("cache");
        Capture c = capture(() -> Jk.execute("cache", "info", "--cache-dir", cache.toString()));
        assertThat(c.exit).isEqualTo(0);
        assertThat(TestAnsi.strip(c.stdout)).contains("Cache");
    }

    @Test
    void cache_storage_is_an_alias_of_cache_usage(@TempDir Path tempDir) {
        Path cache = tempDir.resolve("cache");
        Capture c = capture(() -> Jk.execute("cache", "storage", "--cache-dir", cache.toString()));
        assertThat(c.exit).isEqualTo(0);
        assertThat(TestAnsi.strip(c.stdout)).contains("Cache");
    }

    @Test
    void cache_search_forwards_to_repo_search_with_a_pointer_note(@TempDir Path tempDir) {
        Path cache = tempDir.resolve("cache");
        Capture c = capture(() -> Jk.execute("cache", "search", "nonexistent", "--cache-dir", cache.toString()));
        // Same behavior as jk repo search: exit 1 + "no match" line on an empty store …
        assertThat(c.exit).isEqualTo(1);
        assertThat(TestAnsi.strip(c.stdout)).contains("No cached coordinates match: nonexistent");
        // … plus the one-line pointer to the canonical command, on stderr only.
        assertThat(TestAnsi.strip(c.stderr)).contains("moved to jk repo search");
        assertThat(TestAnsi.strip(c.stdout)).doesNotContain("moved to");
    }

    @Test
    void cache_help_lists_usage_not_hidden_aliases() {
        Capture c = capture(() -> Jk.execute("cache", "--help"));
        assertThat(c.exit).isEqualTo(0);
        String plain = TestAnsi.strip(c.stdout);
        assertThat(plain).contains("usage");
        assertThat(plain).doesNotContain("search");
        assertThat(plain).doesNotContainPattern("(?m)^\\s+info\\b");
        assertThat(plain).doesNotContainPattern("(?m)^\\s+storage\\b");
    }

    // --- helpers -----------------------------------------------------------

    private record Capture(int exit, String stdout, String stderr) {}

    private static Capture capture(IntSupplier body) {
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        System.setErr(new PrintStream(err));
        int exit;
        try {
            exit = body.getAsInt();
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
        return new Capture(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
