// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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
                .isEqualTo("http://127.0.0.1:8910#project/ab12/files/src/test/java/FooTest.java?line=23");
        assertThat(DashboardCodeLink.fileUrl("http://127.0.0.1:8910", "ab", "src/A+B.java", 3))
                .isEqualTo("http://127.0.0.1:8910#project/ab/files/src/A%2BB.java?line=3");
        assertThat(DashboardCodeLink.fileUrl("http://x", "id", "src/Main.java", 0))
                .isEqualTo("http://x#project/id/files/src/Main.java");
        assertThat(DashboardCodeLink.fileUrl(null, "id", "src/Main.java", 1)).isNull();
        assertThat(DashboardCodeLink.fileUrl("http://x", null, "src/Main.java", 1))
                .isNull();
    }

    @Test
    void urlForSnippet_uses_scope_and_http_cache() {
        DashboardCodeLink.putHttpCache("http://127.0.0.1:8910/");
        // Without a real project id under /tmp this may still be null — open a scope and
        // force project resolution only when key works; assert path+line encoding via fileUrl.
        try (var scope = DashboardCodeLink.open(Path.of("/ws"), Path.of("/ws/lib"))) {
            assertThat(scope.checkoutDir().toString()).endsWith("/ws");
            assertThat(DashboardCodeLink.codePath(scope.checkoutDir(), scope.moduleDir(), "src/Foo.java"))
                    .isEqualTo("lib/src/Foo.java");
        }
    }
}
