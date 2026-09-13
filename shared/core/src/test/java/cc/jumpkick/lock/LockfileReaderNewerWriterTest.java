// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A lock this jk cannot read names its remedy by who wrote it: a newer jk means a newer reader is
 * needed and a re-lock would be the wrong move; anything else is a malformed lock to restate.
 */
class LockfileReaderNewerWriterTest {

    private static final String NEWER = "99.0.0";

    @Test
    void a_schema_from_a_newer_jk_asks_for_the_bootstrap_chain_not_a_relock(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("jk-lock.toml");
        Files.writeString(lock, """
                version = 2
                generated-by = "jk NEWER"
                resolution-algorithm = "pubgrub-v1"
                """.replace("NEWER", NEWER));
        assertThatThrownBy(() -> LockfileReader.read(lock))
                .hasMessageContaining("schema version 2 is not supported")
                .hasMessageContaining("written by jk " + NEWER + " and this is jk " + JkVersion.VERSION)
                .hasMessageContaining("The bootstrap chain")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("re-run `jk lock`"));
    }

    @Test
    void a_key_from_a_newer_jk_asks_for_the_bootstrap_chain_too(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("jk-lock.toml");
        Files.writeString(lock, """
                version = 1
                generated-by = "jk NEWER"
                resolution-algorithm = "pubgrub-v1"
                shiny-new-table = "the future"
                """.replace("NEWER", NEWER));
        assertThatThrownBy(() -> LockfileReader.read(lock))
                .hasMessageContaining("unknown top-level key(s) [shiny-new-table]")
                .hasMessageContaining("a newer lock format needs a newer reader");
    }

    @Test
    void the_same_faults_from_this_jk_or_an_older_one_are_a_relock(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("jk-lock.toml");
        Files.writeString(lock, """
                version = 1
                generated-by = "jk 0.1.0"
                resolution-algorithm = "pubgrub-v1"
                typo-key = true
                """);
        assertThatThrownBy(() -> LockfileReader.read(lock))
                .hasMessageContaining("unknown top-level key(s) [typo-key]")
                .hasMessageContaining("re-run `jk lock` to restate it");
        Files.writeString(lock, """
                version = 7
                generated-by = "jk VERSION"
                """.replace("VERSION", JkVersion.VERSION));
        assertThatThrownBy(() -> LockfileReader.read(lock)).hasMessageContaining("re-run `jk lock` to restate it");
    }
}
