// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.machine.MachineEnvironments;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.winhost.ReleasePin;
import cc.jumpkick.winhost.WslDistros;
import cc.jumpkick.winhost.WslSetup;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The machine API behind the engine's own gate: installing or removing needs the engine's token
 * (on Windows the front door's), the engine's epoch, and no other page's origin; a distro WSL does
 * not list is refused before anything runs. Nothing here starts a real {@code wsl.exe}.
 */
@Tag("integration")
class MachineApiServerTest extends HttpEngineServerHarness {

    @TempDir
    Path scratch;

    final List<List<String>> ran = new CopyOnWriteArrayList<>();

    @BeforeEach
    void wireMachine() {
        WslSetup setup = new WslSetup(
                (argv, env, limit, out) -> {
                    ran.add(argv);
                    return 1;
                },
                new WslSetup.Downloads() {
                    @Override
                    public byte[] installer() {
                        return new byte[0];
                    }

                    @Override
                    public byte[] client(String version, String arch) {
                        return new byte[0];
                    }

                    @Override
                    public byte[] engine(String version) {
                        return new byte[0];
                    }
                });
        server.setMachine(new MachineEnvironments(
                () -> List.of(new WslDistros.Distro("Ubuntu-26.10", 2, true, true, "jk 0.51.1")),
                List::of,
                setup,
                () -> new ReleasePin.Pin("0.51.1", "test"),
                scratch,
                "0.51.1",
                Clock.SYSTEM));
    }

    private HttpResponse<String> post(String action, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "api/machine/environments/" + action))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String[] authorized(String... more) throws Exception {
        String[] base = {"Authorization", "Bearer " + token(), "X-Jk-Engine-Epoch", SNAPSHOT.engineEpoch()};
        String[] out = new String[base.length + more.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(more, 0, out, base.length, more.length);
        return out;
    }

    @Test
    void the_listing_needs_the_token() throws Exception {
        assertThat(get("/api/machine/environments", "Authorization", "Bearer wrong")
                        .statusCode())
                .isEqualTo(401);
        HttpResponse<String> list = get("/api/machine/environments");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("\"name\":\"Ubuntu-26.10\"").contains("\"state\":\"installed\"");
    }

    @Test
    void install_and_remove_need_the_token_the_epoch_and_no_other_pages_origin() throws Exception {
        String body = "{\"distros\":[\"Ubuntu-26.10\"]}";
        assertThat(post("install", body).statusCode()).isEqualTo(401);
        assertThat(post("remove", body, "Authorization", "Bearer " + token()).statusCode())
                .isEqualTo(409);
        assertThat(post("install", body, authorized("Origin", "http://evil.example"))
                        .statusCode())
                .isEqualTo(403);
        assertThat(post("remove", body, authorized("Origin", "http://127.0.0.1:" + (port + 1)))
                        .statusCode())
                .isEqualTo(403);
        assertThat(ran).isEmpty();
    }

    @Test
    void a_distro_wsl_does_not_list_is_refused_and_a_listed_one_is_taken() throws Exception {
        HttpResponse<String> unknown = post("install", "{\"distros\":[\"Ubuntu-26.10 --exec sh\"]}", authorized());
        assertThat(unknown.statusCode()).isEqualTo(409);
        assertThat(unknown.body()).contains("WSL lists no distro of that name");
        assertThat(post("install", "{\"distros\":\"Ubuntu\"}", authorized()).statusCode())
                .isEqualTo(400);

        HttpResponse<String> taken =
                post("remove", "{\"distros\":[\"ubuntu-26.10\"]}", authorized("Origin", "http://127.0.0.1:" + port));
        assertThat(taken.statusCode()).isEqualTo(202);
        assertThat(taken.body()).contains("\"accepted\":[\"Ubuntu-26.10\"]");
        // The operation writes under the test's folder until it ends.
        for (int i = 0; i < 250 && !get("/api/machine/environments").body().contains("\"state\":\"failed\""); i++) {
            Thread.sleep(20);
        }
        assertThat(get("/api/machine/environments").body()).contains("\"state\":\"failed\"");
    }
}
