// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.VersionSelector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which {@code [native] metadata-repository} selector a workspace lock resolves.
 *
 * <p>Reading it off the root manifest would have pinned nothing for jk itself: jk's root
 * {@code jk.toml} declares no {@code [native]} table at all, and the only module that does is the
 * CLI. One lock, one extracted repository, so the selector is gathered across every manifest the
 * lock owns.
 */
class LockNativePinTest {

    /** A workspace root needs a resolvable identity before members can inherit it. */
    private static final String ROOT = """
            group   = "com.acme"
            name    = "root"
            version = "1.0.0"

            """;

    @TempDir
    Path root;

    private void manifest(String relDir, String body) throws IOException {
        Path dir = relDir.isEmpty() ? root : Files.createDirectories(root.resolve(relDir));
        Files.writeString(dir.resolve("jk.toml"), body);
    }

    @Test
    void no_native_table_anywhere_declares_no_pin() throws IOException {
        manifest("", "name = \"app\"\n");
        assertThat(LockNativePin.selector(root)).isEmpty();
    }

    @Test
    void a_standalone_project_contributes_its_own_selector() throws IOException {
        manifest("", "name = \"app\"\n\n[native]\nmetadata-repository = \"=1.1.4\"\n");
        assertThat(LockNativePin.selector(root)).contains(VersionSelector.parse("=1.1.4"));
    }

    /** The case jk itself is: the table lives on a member, and the root has none. */
    @Test
    void a_member_declaring_native_pins_the_workspace() throws IOException {
        manifest("", ROOT + "\n[workspace]\nmodules = [\"cli\"]\n");
        manifest("cli", "name = \"cli\"\n\n[native]\nmetadata-repository = \"^1\"\n");
        assertThat(LockNativePin.selector(root)).contains(VersionSelector.parse("^1"));
    }

    /** A table with the key omitted still declares a pin — the parser's default. */
    @Test
    void a_bare_native_table_contributes_the_default() throws IOException {
        manifest("", ROOT + "\n[workspace]\nmodules = [\"cli\"]\n");
        manifest("cli", "name = \"cli\"\n\n[native]\nenabled = \"always\"\n");
        assertThat(LockNativePin.selector(root)).contains(JkBuild.NativeConfig.METADATA_REPOSITORY_DEFAULT);
    }

    /**
     * {@code [application] native = true} builds a native image with no {@code [native]} table.
     * Gating on the table would have taken the reachability metadata away from exactly those
     * projects, silently, the moment the version moved into the lock.
     */
    @Test
    void application_native_true_contributes_without_a_native_table() throws IOException {
        manifest("", "name = \"app\"\n\n[application]\nmain = \"com.example.App\"\nnative = true\n");
        assertThat(LockNativePin.selector(root)).contains(JkBuild.NativeConfig.METADATA_REPOSITORY_DEFAULT);
    }

    /** A declared-but-disabled table builds nothing, so it pays for nothing. */
    @Test
    void a_disabled_native_table_contributes_nothing() throws IOException {
        manifest("", "name = \"app\"\n\n[native]\nenabled = false\nmetadata-repository = \"=1.1.4\"\n");
        assertThat(LockNativePin.selector(root)).isEmpty();
    }

    /**
     * Two members asking for different releases is a question only the author can answer. Picking
     * one would make the image depend on which manifest the loader visited first.
     */
    @Test
    void members_that_disagree_fail_the_lock() throws IOException {
        manifest("", ROOT + "\n[workspace]\nmodules = [\"a\", \"b\"]\n");
        manifest("a", "name = \"a\"\n\n[native]\nmetadata-repository = \"=1.1.4\"\n");
        manifest("b", "name = \"b\"\n\n[native]\nmetadata-repository = \"=2.0.0\"\n");
        assertThatThrownBy(() -> LockNativePin.selector(root))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("=1.1.4")
                .hasMessageContaining("=2.0.0")
                .hasMessageContaining("a/jk.toml")
                .hasMessageContaining("b/jk.toml");
    }

    /** Agreeing members are not a conflict, however many of them there are. */
    @Test
    void members_that_agree_pin_once() throws IOException {
        manifest("", ROOT + "\n[workspace]\nmodules = [\"a\", \"b\"]\n");
        manifest("a", "name = \"a\"\n\n[native]\nmetadata-repository = \"=1.1.4\"\n");
        manifest("b", "name = \"b\"\n\n[native]\nmetadata-repository = \"=1.1.4\"\n");
        assertThat(LockNativePin.selector(root)).contains(VersionSelector.parse("=1.1.4"));
    }
}
