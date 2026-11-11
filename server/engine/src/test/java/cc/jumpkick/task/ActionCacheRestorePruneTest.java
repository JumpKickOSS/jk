// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.PathUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A restore clears what the record does not name, in one walk of the output tree. */
class ActionCacheRestorePruneTest {

    @AfterEach
    void reset() {
        FileHashMemo.reset();
        SessionContext.reset();
    }

    @Test
    void stale_outputs_and_their_directories_go_in_one_walk(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));
        ActionCache ac = new ActionCache(new Cas(tmp.resolve("store")), cache.resolve("actions"));
        Path classes = tmp.resolve("classes");
        Files.createDirectories(classes.resolve("pkg"));
        Files.writeString(classes.resolve("pkg/A.class"), "aaa");
        var rec = ac.store("compile-main", "key", Map.of(), classes);
        Files.createDirectories(classes.resolve("gone/deeper"));
        Files.writeString(classes.resolve("gone/deeper/Old.class"), "old");
        Files.writeString(classes.resolve("pkg/Stale.class"), "stale");
        PathUtil.resetWalks();

        assertThat(ac.restore(rec, classes)).isTrue();

        assertThat(PathUtil.walks())
                .as("the prune is one walk of the output tree")
                .isEqualTo(1);
        assertThat(classes.resolve("pkg/A.class")).hasContent("aaa");
        assertThat(classes.resolve("pkg/Stale.class")).doesNotExist();
        assertThat(classes.resolve("gone")).doesNotExist();
    }
}
