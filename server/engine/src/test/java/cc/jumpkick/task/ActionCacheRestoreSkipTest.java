// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.BuildStamps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A restore over an already-correct tree leaves it alone.
 *
 * <p>{@code restore} must not re-copy byte-identical outputs: re-copying bumps mtime, and
 * {@code FreshnessStamp} compares classpath entries by mtime, so an unchanged tree would
 * invalidate every downstream stamp. These assert the mtime, not the wall clock — downstream
 * staleness is the correctness consequence.
 */
class ActionCacheRestoreSkipTest {

    @AfterEach
    void reset() {
        FileHashMemo.reset();
        SessionContext.reset();
    }

    @Test
    void an_already_correct_tree_keeps_its_mtimes(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));
        ActionCache ac = new ActionCache(new Cas(tmp.resolve("store")), cache.resolve("actions"));

        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Files.createDirectories(classes.resolve("pkg"));
        Files.writeString(classes.resolve("pkg/A.class"), "aaa");
        Files.writeString(classes.resolve("pkg/B.class"), "bbb");
        var rec = ac.store("compile-main", "key", Map.of(), classes);

        // Age the tree so any re-copy is unmistakable.
        FileTime old = FileTime.fromMillis(System.currentTimeMillis() - 600_000);
        Files.setLastModifiedTime(classes.resolve("pkg/A.class"), old);
        Files.setLastModifiedTime(classes.resolve("pkg/B.class"), old);

        assertThat(ac.restore(rec, classes)).isTrue();

        assertThat(Files.getLastModifiedTime(classes.resolve("pkg/A.class")))
                .as("a byte-identical output must not be re-copied")
                .isEqualTo(old);
        assertThat(Files.getLastModifiedTime(classes.resolve("pkg/B.class"))).isEqualTo(old);
        assertThat(Files.readString(classes.resolve("pkg/A.class"))).isEqualTo("aaa");
    }

    @Test
    void a_stale_extra_is_still_removed(@TempDir Path tmp) throws Exception {
        // Pruning must clear what the record does not own, or a restore would leave a class from a
        // previous compile on the classpath. That is why this is a prune and not simply a skip.
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));
        ActionCache ac = new ActionCache(new Cas(tmp.resolve("store")), cache.resolve("actions"));

        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Files.writeString(classes.resolve("Kept.class"), "kept");
        var rec = ac.store("compile-main", "key", Map.of(), classes);

        Files.writeString(classes.resolve("Stale.class"), "left over from a previous compile");

        assertThat(ac.restore(rec, classes)).isTrue();

        assertThat(classes.resolve("Stale.class")).doesNotExist();
        assertThat(Files.readString(classes.resolve("Kept.class"))).isEqualTo("kept");
    }

    @Test
    void a_divergent_output_is_re_copied(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));
        ActionCache ac = new ActionCache(new Cas(tmp.resolve("store")), cache.resolve("actions"));

        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Files.writeString(classes.resolve("A.class"), "cached");
        var rec = ac.store("compile-main", "key", Map.of(), classes);

        Files.writeString(classes.resolve("A.class"), "locally-mangled-different-length");
        FileHashMemo.reset(); // the memo legitimately knows the old digest; force the disk comparison

        assertThat(ac.restore(rec, classes)).isTrue();
        assertThat(Files.readString(classes.resolve("A.class"))).isEqualTo("cached");
    }

    @Test
    void freshness_stamps_survive_the_restore(@TempDir Path tmp) throws Exception {
        // The stamps live inside the classes tree but are written by a *later* step, so a restore
        // must not take them with it. They are owned, which keeps their mtime too.
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));
        ActionCache ac = new ActionCache(new Cas(tmp.resolve("store")), cache.resolve("actions"));

        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Files.writeString(classes.resolve("A.class"), "aaa");
        var rec = ac.store("compile-main", "key", Map.of(), classes);

        Path stamp = classes.resolve(BuildStamps.ALL.iterator().next());
        Files.writeString(stamp, "stamp-body");

        assertThat(ac.restore(rec, classes)).isTrue();

        assertThat(Files.readString(stamp)).isEqualTo("stamp-body");
    }
}
