// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.audit;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The audit's requests go the way every other jk download does: through the proxy the request's
 * shell names. The proxy is a loopback stub that answers for whatever host it is asked about, so
 * {@code osv.example.test} — a reserved name that resolves nowhere — is reachable only through it.
 */
class OsvClientProxyTest {

    @Test
    void a_batch_query_goes_through_the_proxy_the_shell_names(@TempDir Path home) throws Exception {
        List<String> hosts = new CopyOnWriteArrayList<>();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            hosts.add(exchange.getRequestHeaders().getFirst("Host") + " "
                    + exchange.getRequestURI().getPath());
            byte[] body = "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-via-proxy\"}]}]}".getBytes(StandardCharsets.UTF_8);
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
            OsvClient client = new OsvClient(
                    URI.create("http://osv.example.test/v1/querybatch"),
                    URI.create("http://osv.example.test/v1/vulns/"));

            List<OsvClient.Result> results = SessionContext.where(
                    request, () -> client.queryBatch(List.of(new OsvClient.Query("Maven", "g:a", "1.0"))));

            assertThat(results)
                    .singleElement()
                    .extracting(OsvClient.Result::vulnIds)
                    .isEqualTo(List.of("GHSA-via-proxy"));
            assertThat(hosts).containsExactly("osv.example.test /v1/querybatch");
        } finally {
            System.clearProperty("jk.env.JK_HOME");
            proxy.stop(0);
        }
    }
}
