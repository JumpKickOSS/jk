// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An upload that fails leaves its file closed: a server that hangs up before the body is read does
 * not keep the file open, where Windows would refuse to delete it and a build to overwrite it.
 */
class HttpPutFileReleaseTest {

    @Test
    void a_failed_upload_leaves_the_file_closed(@TempDir Path tmp) throws Exception {
        for (int size : new int[] {4, 4 * 1024 * 1024}) {
            Path file = Files.write(tmp.resolve("blob-" + size), new byte[size]);
            try (ServerSocket hangUp = new ServerSocket()) {
                hangUp.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                Thread acceptor = Thread.ofPlatform().daemon().start(() -> {
                    while (!hangUp.isClosed()) {
                        try (Socket client = hangUp.accept()) {
                            // Hang up without reading the request.
                        } catch (IOException stop) {
                            return;
                        }
                    }
                });
                URI uri = URI.create("http://" + hangUp.getInetAddress().getHostAddress() + ":" + hangUp.getLocalPort()
                        + "/cas/blob");

                assertThatThrownBy(() -> new Http(Http.standardClient(), new Duration[0]).putFile(uri, file, Map.of()))
                        .isInstanceOf(IOException.class);
                hangUp.close();
                acceptor.join();
            }

            assertNotOpen(file);
        }
    }

    /**
     * Fails while this JVM holds {@code file} open: on Windows the delete is refused, and on Linux
     * the file is among this process's open descriptors.
     */
    static void assertNotOpen(Path file) throws IOException {
        Path fds = Path.of("/proc/self/fd");
        if (Files.isDirectory(fds)) {
            Path real = file.toRealPath();
            try (Stream<Path> open = Files.list(fds)) {
                assertThat(open.map(HttpPutFileReleaseTest::target))
                        .describedAs("files this process holds open")
                        .doesNotContain(real.toString());
            }
        }
        Files.delete(file);
    }

    private static String target(Path fd) {
        try {
            return Files.readSymbolicLink(fd).toString();
        } catch (IOException | UnsupportedOperationException gone) {
            return "";
        }
    }
}
