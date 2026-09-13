// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SDK license feed is a download like any other: it goes through the proxy the request's shell
 * names. The proxy is a loopback stub answering for any host, so {@code dl.example.test} — a name
 * that resolves nowhere — is reachable only through it.
 */
class AndroidLicensesFeedTest {

    private static final String FEED = "<sdk:sdk-repository>"
            + "<license id=\"android-sdk-license\" type=\"text\">\n  Terms and Conditions\n</license>"
            + "<license id=\"android-sdk-preview-license\" type=\"text\">Preview terms</license>"
            + "</sdk:sdk-repository>";

    @Test
    void the_feed_is_fetched_through_the_proxy_the_shell_names(@TempDir Path home) throws Exception {
        List<String> hosts = new CopyOnWriteArrayList<>();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            hosts.add(exchange.getRequestHeaders().getFirst("Host"));
            byte[] body = FEED.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        proxy.start();
        Files.writeString(home.resolve("config.toml"), "");
        System.setProperty("jk.env.JK_HOME", home.toString());
        try {
            Session request = Session.defaults()
                    .withVariant(
                            null,
                            Map.of(
                                    "http_proxy",
                                    "http://127.0.0.1:" + proxy.getAddress().getPort()));

            Map<String, String> licenses = SessionContext.where(
                    request,
                    () -> AndroidCommand.fetchLicenses("http://dl.example.test/android/repository/repository2-3.xml"));

            assertThat(licenses)
                    .containsEntry("android-sdk-license", "Terms and Conditions")
                    .containsEntry("android-sdk-preview-license", "Preview terms");
            assertThat(hosts).containsExactly("dl.example.test");
        } finally {
            System.clearProperty("jk.env.JK_HOME");
            proxy.stop(0);
        }
    }

    @Test
    void a_file_feed_is_read_without_any_client(@TempDir Path dir) throws Exception {
        Path feed = dir.resolve("repository2-3.xml");
        Files.writeString(feed, FEED);

        assertThat(AndroidCommand.fetchLicenses(feed.toUri().toString())).containsKey("android-sdk-license");
    }
}
