// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.wire.protocol.CacheInventoryAck;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lock row says what the build would: whether the lock is stale is the engine's answer, where
 * the plugins' manifests are installed. Decided in the client, a module with {@code [jooq]} had a
 * lock called stale right after {@code jk lock} while build and explain took it as fresh, since a
 * plugin table has no owner there.
 */
class DoctorLockTest {

    private static Path project(Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "g"
                name = "jq"
                version = "1"
                java = 25

                [jooq]
                version = "3.21.9"
                package = "demo.db"
                """);
        // No manifests-sha256 stamp: a decision taken here, rather than asked, would call it stale.
        Files.writeString(dir.resolve("jk-lock.toml"), LockfileWriter.render(Lockfile.empty("test")));
        return dir;
    }

    @Test
    void the_lock_is_as_fresh_as_the_engine_says(@TempDir Path dir) throws Exception {
        DoctorCommand.Check check = DoctorCommand.checkLock(project(dir), p -> CacheInventoryAck.lockFreshness(null));

        assertThat(check.status()).isEqualTo(DoctorCommand.Status.OK);
        assertThat(check.detail())
                .contains("artifacts")
                .contains("jk-lock.toml")
                .doesNotContain("stale");
    }

    @Test
    void a_stale_lock_is_named_with_the_engines_reason(@TempDir Path dir) throws Exception {
        String reason = "jk-lock.toml is stale: it has no [[tool]] row for org.jooq:jooq-codegen:3.21.9 (jq)";
        DoctorCommand.Check check = DoctorCommand.checkLock(project(dir), p -> CacheInventoryAck.lockFreshness(reason));

        assertThat(check.status()).isEqualTo(DoctorCommand.Status.WARN);
        assertThat(check.detail()).startsWith(reason).contains("run jk lock");
    }

    @Test
    void with_no_engine_to_ask_staleness_is_left_unchecked_and_said(@TempDir Path dir) throws Exception {
        DoctorCommand.Check skipped = DoctorCommand.checkLock(project(dir), null);
        assertThat(skipped.status()).isEqualTo(DoctorCommand.Status.OK);
        assertThat(skipped.detail()).contains("not checked for staleness: not asked (--no-engine)");

        DoctorCommand.Check failed =
                DoctorCommand.checkLock(project(dir), p -> CacheInventoryAck.error("unknown cache inventory query"));
        assertThat(failed.detail()).contains("not checked for staleness: engine query failed");
    }
}
