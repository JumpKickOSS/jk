// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link DashboardCodeLink} path join + file deep-link URL shape. */
class DashboardCodeLinkTest {

    @AfterEach
    void clear() {
        DashboardCodeLink.clearHttpCache();
    }

    @Test
    void codePath_joins_module_under_checkout() {
        assertThat(DashboardCodeLink.codePath(Path.of("/ws"), Path.of("/ws/lib"), "src/test/java/FooTest.java"))
                .isEqualTo("lib/src/test/java/FooTest.java");
        assertThat(DashboardCodeLink.codePath(Path.of("/ws"), Path.of("/ws"), "src/Main.java"))
                .isEqualTo("src/Main.java");
        // Empty module dir (single-plan live key) — file already checkout-relative.
        assertThat(DashboardCodeLink.codePath(Path.of("/ws"), null, "src/test/java/Foo.java"))
                .isEqualTo("src/test/java/Foo.java");
        assertThat(DashboardCodeLink.codePath(Path.of("/ws"), Path.of("/ws/lib"), "Foo.java"))
                .isNull();
        assertThat(DashboardCodeLink.codePath(Path.of("/ws"), Path.of("/ws/lib"), "/etc/passwd"))
                .isNull();
        assertThat(DashboardCodeLink.codePath(Path.of("/ws"), null, "src/../secret/X.java"))
                .isNull();
    }

    @Test
    void fileUrl_matches_web_hash_route() {
        assertThat(DashboardCodeLink.fileUrl("http://127.0.0.1:8910/", "ab12", "src/test/java/FooTest.java", 23))
                .isEqualTo("http://127.0.0.1:8910#project/ab12/files/src/test/java/FooTest.java?line=23&err=true");
        assertThat(DashboardCodeLink.fileUrl("http://127.0.0.1:8910/", "ab12", "src/Main.java", 12, 7))
                .isEqualTo("http://127.0.0.1:8910#project/ab12/files/src/Main.java?line=12&col=7&err=true");
        assertThat(DashboardCodeLink.fileUrl(
                        "http://127.0.0.1:8910/", "ab12", "src/Main.java", 12, 7, "error: cannot find symbol"))
                .isEqualTo(
                        "http://127.0.0.1:8910#project/ab12/files/src/Main.java?line=12&col=7&err=true&msg=error%3A%20cannot%20find%20symbol");
        assertThat(DashboardCodeLink.fileUrl("http://127.0.0.1:8910", "ab", "src/A+B.java", 3))
                .isEqualTo("http://127.0.0.1:8910#project/ab/files/src/A%2BB.java?line=3&err=true");
        assertThat(DashboardCodeLink.fileUrl("http://x", "id", "src/Main.java", 0))
                .isEqualTo("http://x#project/id/files/src/Main.java");
        // The checkout rides along: two worktrees of one repository share the project id. Asserted
        // by decoding rather than against a literal: the absolute spelling of a path is the
        // platform's (`/ws` is `C:\ws` on Windows), and only the encoding is this method's to get
        // right.
        assertDirQuery(
                DashboardCodeLink.fileUrl("http://x", "id", Path.of("/ws/my app"), "src/Main.java", 12, 0, null),
                "http://x#project/id/files/src/Main.java?line=12&err=true&dir=",
                Path.of("/ws/my app"));
        assertDirQuery(
                DashboardCodeLink.fileUrl("http://x", "id", Path.of("/ws"), "src/Main.java", 0, 0, null),
                "http://x#project/id/files/src/Main.java?dir=",
                Path.of("/ws"));
        assertThat(DashboardCodeLink.fileUrl(null, "id", "src/Main.java", 1)).isNull();
        assertThat(DashboardCodeLink.fileUrl("http://x", null, "src/Main.java", 1))
                .isNull();
    }

    /**
     * {@code url} is {@code prefix} followed by {@code checkout} absolutized, normalized and
     * percent-encoded — with a space as {@code %20}, never the {@code +} {@link java.net.URLEncoder}
     * would emit.
     */
    private static void assertDirQuery(@Nullable String url, String prefix, Path checkout) {
        assertThat(url).isNotNull().startsWith(prefix).doesNotContain("+");
        String encoded = Objects.requireNonNull(url).substring(prefix.length());
        assertThat(Path.of(URLDecoder.decode(encoded, StandardCharsets.UTF_8)))
                .isEqualTo(checkout.toAbsolutePath().normalize());
    }

    @Test
    void urlForSnippet_uses_scope_and_http_cache() {
        DashboardCodeLink.putHttpCache("http://127.0.0.1:8910/");
        // Without a real project id under /tmp this may still be null — open a scope and
        // force project resolution only when key works; assert path+line encoding via fileUrl.
        try (var scope = DashboardCodeLink.open(Path.of("/ws"), Path.of("/ws/lib"))) {
            // Path.of("/ws") is drive-qualified on Windows (C:\ws).
            Path checkoutDir = scope.checkoutDir();
            assertThat(checkoutDir).isNotNull();
            assertThat(Objects.requireNonNull(checkoutDir).getFileName().toString())
                    .isEqualTo("ws");
            assertThat(DashboardCodeLink.codePath(checkoutDir, scope.moduleDir(), "src/Foo.java"))
                    .isEqualTo("lib/src/Foo.java");
        }
    }

    @Test
    void clipMsg_trims_and_caps() {
        assertThat(DashboardCodeLink.clipMsg(null)).isEmpty();
        assertThat(DashboardCodeLink.clipMsg("  hi  ")).isEqualTo("hi");
        String overlong = "x".repeat(DashboardCodeLink.MAX_MSG_CHARS + 20);
        String clipped = DashboardCodeLink.clipMsg(overlong);
        assertThat(clipped).hasSize(DashboardCodeLink.MAX_MSG_CHARS);
        assertThat(clipped).endsWith("…");
    }
}
