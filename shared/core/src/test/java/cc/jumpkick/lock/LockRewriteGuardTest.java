// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A jk older than the lock's writer does not rewrite the lock: by version first, and between two
 * builds of one version by the build's time. A newer jk, the same build, an unstamped lock and
 * {@code --force} all go ahead.
 */
class LockRewriteGuardTest {

    private static final Instant EARLIER = Instant.parse("2026-09-16T13:12:00Z");
    private static final Instant LATER = Instant.parse("2026-09-17T15:57:16Z");

    private static Lockfile writtenBy(String version, @Nullable WriterBuild build) {
        return Lockfile.empty(version).withWriterBuild(build);
    }

    @Test
    void a_lock_a_newer_version_wrote_is_refused_naming_both() {
        String refusal = LockRewriteGuard.refusal(writtenBy("0.14.0", null), "0.13.7", null);

        assertThat(refusal)
                .contains("written by jk 0.14.0")
                .contains("this engine is jk 0.13.7, an older jk")
                .contains("`jk engine stop`")
                .contains("`jk lock --force`");
    }

    @Test
    void a_newer_jk_rewrites_an_older_writers_lock() {
        assertThat(LockRewriteGuard.refusal(writtenBy("0.13.6", null), "0.13.7", null))
                .isNull();
        WriterBuild theirs = new WriterBuild("aaaaaaaaaaaa", LATER);
        WriterBuild ours = new WriterBuild("bbbbbbbbbbbb", EARLIER);
        assertThat(LockRewriteGuard.refusal(writtenBy("0.13.6", theirs), "0.13.7", ours))
                .as("the version decides before the build does")
                .isNull();
    }

    @Test
    void the_same_version_is_ordered_by_the_builds_time() {
        WriterBuild newer = new WriterBuild("aaaaaaaaaaaa", LATER);
        WriterBuild older = new WriterBuild("bbbbbbbbbbbb", EARLIER);

        assertThat(LockRewriteGuard.refusal(writtenBy("0.13.7", newer), "0.13.7", older))
                .contains("written by jk 0.13.7 (build aaaaaaaaaaaa of 2026-09-17T15:57:16Z)")
                .contains("this engine is jk 0.13.7 (build bbbbbbbbbbbb of 2026-09-16T13:12:00Z)")
                .contains("an older build of the same version");
        assertThat(LockRewriteGuard.refusal(writtenBy("0.13.7", older), "0.13.7", newer))
                .isNull();
        assertThat(LockRewriteGuard.refusal(writtenBy("0.13.7", newer), "0.13.7", newer))
                .as("the build that wrote it")
                .isNull();
    }

    @Test
    void a_missing_stamp_on_either_side_is_no_opinion() {
        WriterBuild stamped = new WriterBuild("aaaaaaaaaaaa", LATER);
        WriterBuild untimed = new WriterBuild("cccccccccccc", null);

        assertThat(LockRewriteGuard.refusal(writtenBy("0.13.7", null), "0.13.7", stamped))
                .isNull();
        assertThat(LockRewriteGuard.refusal(writtenBy("0.13.7", stamped), "0.13.7", null))
                .isNull();
        assertThat(LockRewriteGuard.refusal(writtenBy("0.13.7", stamped), "0.13.7", untimed))
                .isNull();
        assertThat(LockRewriteGuard.refusal(writtenBy("0.13.7", untimed), "0.13.7", stamped))
                .isNull();
        assertThat(LockRewriteGuard.refusal(Lockfile.empty("0.13.7").withGeneratedBy("by hand"), "0.13.7", null))
                .as("a writer that is not a jk version")
                .isNull();
    }

    @Test
    void force_and_a_missing_or_unreadable_lock_go_ahead(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("jk-lock.toml");
        assertThatCode(() -> LockRewriteGuard.refuseUnlessForced(lock, false)).doesNotThrowAnyException();

        Files.writeString(lock, LockfileWriter.render(Lockfile.empty("99.0.0")));
        LockfileReader.clearCache();
        assertThatThrownBy(() -> LockRewriteGuard.refuseUnlessForced(lock, false))
                .isInstanceOf(LockRewriteGuard.LockRewriteRefused.class)
                .hasMessageContaining("written by jk 99.0.0");
        assertThatCode(() -> LockRewriteGuard.refuseUnlessForced(lock, true)).doesNotThrowAnyException();

        Files.writeString(lock, "not = [a lock");
        LockfileReader.clearCache();
        assertThatCode(() -> LockRewriteGuard.refuseUnlessForced(lock, false))
                .as("an unreadable lock is restated by the relock")
                .doesNotThrowAnyException();
    }
}
