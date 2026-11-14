// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.ide.IdeEngineClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A build server whose jk updated itself cannot drive the newer engine: the request that finds the
 * update is answered with an error naming the release, and the server ends so the IDE starts the
 * new one, rather than failing every later request.
 */
class BspServerUpdateTest {

    @Test
    void the_request_after_an_update_is_refused_and_the_server_ends(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), "name = \"t\"\nversion = \"0.0.1\"\n");
        IdeEngineClient ide = IdeEngineClient.open(dir, dir.resolve("cache"), null);
        String session = frame(request(1, "workspace/buildTargets")) + frame(request(2, "build/shutdown"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        new BspServer(
                        ide,
                        new ByteArrayInputStream(session.getBytes(StandardCharsets.UTF_8)),
                        out,
                        () -> Optional.of("9.9.9"))
                .serve();

        String responses = out.toString(StandardCharsets.UTF_8);
        assertThat(responses).contains("\"id\":1").contains("JumpKick was updated to 9.9.9");
        assertThat(responses).as("nothing after the update is served").doesNotContain("\"id\":2");
    }

    private static String request(int id, String method) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":{}}";
    }

    private static String frame(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return "Content-Length: " + bytes.length + "\r\n\r\n" + body;
    }
}
