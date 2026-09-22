// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A cached URL script name is one path segment inside its hash directory. */
class UrlToolSourcePathTest {

    @Test
    void a_name_that_leaves_the_cache_fails_and_a_plain_name_caches(@TempDir Path tmp) throws Exception {
        byte[] body = "class Main { public static void main(String[] a) {} }\n".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            Path sentinel = tmp.resolve("outside.java");
            Files.writeString(sentinel, "keep");
            Path cache = tmp.resolve("cache");
            assertThatThrownBy(() ->
                            UrlToolSource.fetch("http://127.0.0.1:" + port + "/..%5C..%5Coutside.java", cache, false))
                    .isInstanceOf(Exception.class);
            assertThat(Files.readString(sentinel)).isEqualTo("keep");
            assertThat(tmp.resolve("outside.java")).exists();

            Path cached = UrlToolSource.fetch("http://127.0.0.1:" + port + "/main.java", cache, false);
            assertThat(cached).isRegularFile();
            Path parent = cached.getParent();
            assertThat(parent).isNotNull().startsWith(cache.toAbsolutePath().normalize());
            assertThat(Files.readString(cached)).contains("class Main");
        } finally {
            server.stop(0);
        }
    }
}
