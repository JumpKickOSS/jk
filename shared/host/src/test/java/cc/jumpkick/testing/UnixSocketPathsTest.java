// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.PathUtil;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The budget, proven by binding. {@link UnixSocketPaths#MAX_PATH_LENGTH} is the longest path the
 * JDK will accept; a 103-byte path fails even though it is inside the raw {@code sun_path} sizes.
 */
@DisabledOnOs(OS.WINDOWS) // no Unix domain sockets to budget for
class UnixSocketPathsTest {

    @Test
    void a_path_at_the_budget_binds() throws Exception {
        Path dir = Files.createTempDirectory(ShortTempDirs.root(), "jk-budget-");
        try {
            Path at = pathOfLength(dir, UnixSocketPaths.MAX_PATH_LENGTH);
            assertThat(at.toString()).hasSize(UnixSocketPaths.MAX_PATH_LENGTH);
            try (ServerSocketChannel ch = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                ch.bind(UnixDomainSocketAddress.of(at));
            }
            Files.deleteIfExists(at);
        } finally {
            PathUtil.deleteRecursively(dir);
        }
    }

    @Test
    void the_short_root_leaves_room_for_a_real_engine_socket_name() throws Exception {
        // …/<temp dir name>/engine/<16 hex>.gen1.sock is what the engine composes under a root.
        String suffix = "/jk-state-mtczg28v/engine/2a52286a96107a5d.gen1.sock";
        assertThat(ShortTempDirs.root().toString().length() + suffix.length())
                .as("a socket under a fresh dir in the short root must fit the budget")
                .isLessThanOrEqualTo(UnixSocketPaths.MAX_PATH_LENGTH);
    }

    /** A path under {@code dir} whose full string is exactly {@code length} characters. */
    private static Path pathOfLength(Path dir, int length) {
        int need = length - dir.toString().length() - 1;
        assertThat(need)
                .as("temp root %s is too long to compose a %d-char probe", dir, length)
                .isGreaterThan(0);
        return dir.resolve("s".repeat(need));
    }
}
