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
 * A jk older than the lock's writer — by version, or between two builds of one version by the
 * build's time — rewrites the lock only when it restates every key the lock carries. A newer jk,
 * the same build, an unstamped lock and {@code --force} all go ahead.
 */
class LockRewriteGuardTest {

    private static final Instant EARLIER = Instant.parse("2026-09-16T13:12:00Z");
    private static final Instant LATER = Instant.parse("2026-09-17T15:57:16Z");

    /** A table key this jk's reader passes over and its writer never emits. */
    private static final String UNKNOWN_KEY = "\n[jdk]\nsuggested-vendor = \"temurin\"\nfuture-key = \"x\"\n";

    private static Lockfile writtenBy(String version, @Nullable WriterBuild build) {
        return Lockfile.empty(version).withWriterBuild(build);
    }

    /** {@code lock} as its writer rendered it, plus a key this jk does not know. */
    private static String withUnknownKey(Lockfile lock) {
        return LockfileWriter.render(lock) + UNKNOWN_KEY;
    }

    private static @Nullable String refusal(Lockfile lock, String runningVersion, @Nullable WriterBuild running) {
        String text = withUnknownKey(lock);
        return LockRewriteGuard.refusal(text, LockfileReader.parse(text), runningVersion, running);
    }

    @Test
    void a_newer_versions_lock_with_a_key_this_jk_does_not_know_is_refused_naming_it() {
        String refusal = refusal(writtenBy("0.14.0", null), "0.13.7", null);

        assertThat(refusal)
                .contains("written by jk 0.14.0")
                .contains("this engine is jk 0.13.7, an older jk that does not know its key(s) jdk.future-key")
                .contains("`jk engine stop`")
                .contains("`jk lock --force`");
    }

    @Test
    void a_newer_writers_lock_this_jk_restates_whole_is_rewritten() {
        Lockfile lock = writtenBy("0.14.0", new WriterBuild("aaaaaaaaaaaa", LATER));
        String text = LockfileWriter.render(lock);

        assertThat(LockRewriteGuard.refusal(text, lock, "0.13.7", new WriterBuild("bbbbbbbbbbbb", EARLIER)))
                .isNull();
    }

    @Test
    void a_newer_jk_rewrites_an_older_writers_lock() {
        assertThat(refusal(writtenBy("0.13.6", null), "0.13.7", null)).isNull();
        WriterBuild theirs = new WriterBuild("aaaaaaaaaaaa", LATER);
        WriterBuild ours = new WriterBuild("bbbbbbbbbbbb", EARLIER);
        assertThat(refusal(writtenBy("0.13.6", theirs), "0.13.7", ours))
                .as("the version decides before the build does")
                .isNull();
    }

    @Test
    void the_same_version_is_ordered_by_the_builds_time() {
        WriterBuild newer = new WriterBuild("aaaaaaaaaaaa", LATER);
        WriterBuild older = new WriterBuild("bbbbbbbbbbbb", EARLIER);

        assertThat(refusal(writtenBy("0.13.7", newer), "0.13.7", older))
                .contains("written by jk 0.13.7 (build aaaaaaaaaaaa of 2026-09-17T15:57:16Z)")
                .contains("this engine is jk 0.13.7 (build bbbbbbbbbbbb of 2026-09-16T13:12:00Z)")
                .contains("an older build of the same version");
        assertThat(refusal(writtenBy("0.13.7", older), "0.13.7", newer)).isNull();
        assertThat(refusal(writtenBy("0.13.7", newer), "0.13.7", newer))
                .as("the build that wrote it")
                .isNull();
    }

    @Test
    void a_missing_stamp_on_either_side_is_no_opinion() {
        WriterBuild stamped = new WriterBuild("aaaaaaaaaaaa", LATER);
        WriterBuild untimed = new WriterBuild("cccccccccccc", null);

        assertThat(refusal(writtenBy("0.13.7", null), "0.13.7", stamped)).isNull();
        assertThat(refusal(writtenBy("0.13.7", stamped), "0.13.7", null)).isNull();
        assertThat(refusal(writtenBy("0.13.7", stamped), "0.13.7", untimed)).isNull();
        assertThat(refusal(writtenBy("0.13.7", untimed), "0.13.7", stamped)).isNull();
        assertThat(refusal(Lockfile.empty("0.13.7").withGeneratedBy("by hand"), "0.13.7", null))
                .as("a writer that is not a jk version")
                .isNull();
    }

    @Test
    void a_dropped_key_inside_a_row_is_named_by_its_row_table() {
        String text = LockfileWriter.render(Lockfile.empty("0.14.0")) + """

                [[artifact]]
                name     = "aopalliance:aopalliance:jar:"
                version  = "1.0"
                source   = "central+https://repo.maven.apache.org/maven2/"
                checksum = "sha256:0addec670fedcd3f113c5c8091d783280d23f75e3acb841b61a9cdb079376a08"
                scopes   = ["provided", "test"]
                mirror   = "elsewhere"
                """;

        assertThat(LockRewriteGuard.droppedKeys(text, LockfileReader.parse(text)))
                .containsExactly("artifact[].mirror");
    }

    @Test
    void force_and_a_missing_or_unreadable_lock_go_ahead(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("jk-lock.toml");
        assertThatCode(() -> LockRewriteGuard.refuseUnlessForced(lock, false)).doesNotThrowAnyException();

        Files.writeString(lock, withUnknownKey(Lockfile.empty("99.0.0")));
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
