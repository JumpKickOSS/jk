// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The clients an install staged in the PATH bin and a crash kept it from renaming into place. */
class EngineInstallStagedClientTest {

    /** A crash between staging and the rename leaves a staged client; gc takes it once no install can still own it. */
    @Test
    void gc_deletes_a_staged_client_past_the_age_bound_and_keeps_a_fresh_one(@TempDir Path homeRoot) throws Exception {
        Path home = Files.createDirectories(homeRoot.resolve("home"));
        AtomicLong clock = new AtomicLong(1_724_400_000_000L);
        EngineInstall store = new EngineInstall(home.resolve("lib"), clock::get);
        Path bin = Files.createDirectories(home.resolve("bin"));
        long stale = clock.get() - Duration.ofHours(2).toMillis();
        long fresh = clock.get() - Duration.ofMinutes(1).toMillis();
        Path staleJk = stagedAt(bin.resolve(".jk-new-1111"), stale);
        Path staleJkx = stagedAt(bin.resolve(".jkx.exe-new-1111"), stale);
        Path freshJk = stagedAt(bin.resolve(".jk-new-2222"), fresh);
        Path freshJkx = stagedAt(bin.resolve(".jkx-new-2222"), fresh);
        Path notOurs = stagedAt(bin.resolve(".jq-new-3333"), stale);

        List<Path> pruned = store.gc(bin);

        assertThat(pruned).containsExactlyInAnyOrder(staleJk, staleJkx);
        assertThat(freshJk).exists();
        assertThat(freshJkx).exists();
        assertThat(notOurs).exists();
    }

    @Test
    void an_install_sweeps_a_stale_staged_client_and_leaves_a_fresh_one(@TempDir Path tmp) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        long now = System.currentTimeMillis();
        Path stale =
                stagedAt(bin.resolve(".jk-new-1111"), now - Duration.ofHours(2).toMillis());
        Path fresh = stagedAt(bin.resolve(".jk-new-2222"), now);

        EngineInstall.installBinaries(Files.writeString(tmp.resolve("new-jk"), "new-jk"), bin, false);

        assertThat(stale).doesNotExist();
        assertThat(fresh).exists();
    }

    private static Path stagedAt(Path p, long mtimeMillis) throws IOException {
        Files.writeString(p, "staged");
        Files.setLastModifiedTime(p, FileTime.fromMillis(mtimeMillis));
        return p;
    }
}
