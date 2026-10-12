// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * OSV paginates a large hit set: a result carries {@code next_page_token}, and the next page is
 * the same query re-posted with {@code page_token}. The stub answers from the request body, so each
 * test states what every page returns.
 */
class OsvClientPaginationTest {

    private final List<String> postBodies = new CopyOnWriteArrayList<>();
    private @Nullable HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void a_result_with_a_next_page_token_is_followed_until_the_token_clears() throws Exception {
        OsvClient client = stub(body -> body.contains("\"page_token\"")
                ? "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-a-1\"},{\"id\":\"GHSA-a-2\"}]}]}"
                : "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-a-1\"}],\"next_page_token\":\"t1\"},"
                        + "{\"vulns\":[{\"id\":\"GHSA-b-1\"}]}]}");

        List<OsvClient.Result> results = client.queryBatch(
                List.of(new OsvClient.Query("Maven", "g:a", "1.0"), new OsvClient.Query("Maven", "g:b", "2.0")));

        assertThat(results)
                .extracting(OsvClient.Result::vulnIds)
                .containsExactly(List.of("GHSA-a-1", "GHSA-a-2"), List.of("GHSA-b-1"));
        assertThat(postBodies).hasSize(2);
        assertThat(postBodies.get(1))
                .as("only the query that carried a token is re-posted, with that token")
                .contains("\"name\":\"g:a\"")
                .contains("\"page_token\":\"t1\"")
                .doesNotContain("g:b");
    }

    @Test
    void a_token_that_never_clears_fails_the_audit_instead_of_reporting_a_partial_set() throws Exception {
        OsvClient client =
                stub(body -> "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-x\"}],\"next_page_token\":\"again\"}]}");

        assertThatThrownBy(() -> client.queryBatch(List.of(new OsvClient.Query("Maven", "g:a", "1.0"))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("next_page_token")
                .hasMessageContaining("after " + OsvClient.MAX_PAGES + " pages");
        assertThat(postBodies).hasSize(OsvClient.MAX_PAGES);
    }

    private OsvClient stub(Function<String, String> responseFor) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server = server;
        server.createContext("/", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            postBodies.add(request);
            byte[] body = responseFor.apply(request).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new OsvClient(base.resolve("/v1/querybatch"), base.resolve("/v1/vulns/"));
    }
}
