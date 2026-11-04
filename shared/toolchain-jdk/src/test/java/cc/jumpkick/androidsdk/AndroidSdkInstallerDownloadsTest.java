// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.androidsdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.task.IoLedger;
import cc.jumpkick.task.TransferBoard;
import cc.jumpkick.testing.FakeClock;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An SDK archive shows on the run's download board beside every other download. */
class AndroidSdkInstallerDownloadsTest {

    private final FakeClock clock = new FakeClock();
    private final IoLedger io = new IoLedger(clock);

    @Test
    void the_archive_streams_onto_the_run_board_sized_by_the_feed_when_the_server_declares_nothing(@TempDir Path dir)
            throws Exception {
        byte[] bytes = new byte[100_000];
        AndroidRepoFeed.Archive archive = new AndroidRepoFeed.Archive("platform-28_r06.zip", "", bytes.length, "");
        AtomicReference<TransferBoard.Snapshot> midway = new AtomicReference<>();
        InputStream body = watching(new ByteArrayInputStream(bytes), midway);
        Path target = dir.resolve("platform.zip");
        MessageDigest sha1 = Hashing.newDigest("SHA-1");

        SessionContext.where(Session.defaults().withIo(io), () -> {
            AndroidSdkInstaller.receive(body, -1L, archive, "platforms;android-28", sha1, target);
            return null;
        });

        assertThat(midway.get().rows()).singleElement().satisfies(row -> {
            assertThat(row.label()).isEqualTo("Android SDK platforms;android-28");
            assertThat(row.total()).as("the feed's size stands in").isEqualTo(bytes.length);
        });
        TransferBoard.Snapshot after = io.transfers().snapshot();
        assertThat(after.files()).isEqualTo(1);
        assertThat(after.bytes()).isEqualTo(bytes.length);
        assertThat(after.inflight()).isZero();
        assertThat(Files.size(target)).isEqualTo(bytes.length);
    }

    /**
     * {@code in}, moving the clock past the dwell on its second read and recording the board then:
     * the transfer has started and run long enough to have a row.
     */
    private InputStream watching(InputStream in, AtomicReference<TransferBoard.Snapshot> midway) {
        return new FilterInputStream(in) {
            private int reads;

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (++reads == 2) {
                    clock.advance(Duration.ofMillis(300));
                    midway.set(io.transfers().snapshot());
                }
                return super.read(b, off, len);
            }
        };
    }
}
