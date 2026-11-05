// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.IsolatedHome;
import cc.jumpkick.cli.testing.RecordingProxy;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.util.JkDirs;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * Credentials whose secret the user's {@code ~/.jk/config.toml} names by reference, driven through
 * {@code jk lock} against a repository that checks the token: a {@code ${file:}} secret rotated
 * between two locks is read again with the engine still running, and a {@code ${cmd:}} helper
 * whose token the repository stops accepting is run once more and the request retried, on a
 * repository and on a native mirror.
 */
@Tag("integration")
@IsolatedHome
class UserConfigSecretsTest {

    private static final String GROUP = "com.foo.secret";
    private static final String ARTIFACT = "lib";

    /** Central (the JUnit defaults) at the root, and the private repository under {@link #SECURED}. */
    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private static final String SECURED = "/secured";

    /** The token the secured paths accept now; anything else is a 401. */
    private final AtomicReference<String> accepted = new AtomicReference<>("");

    /** The {@code Authorization} each secured request for a resolved artifact carried, in arrival order. */
    private final List<String> presented = new CopyOnWriteArrayList<>();

    /** The user's proxy, so a fresh engine's off-machine requests fail here rather than reach the internet. */
    @RegisterExtension
    final RecordingProxy proxy = new RecordingProxy();

    private @Nullable String homeConfig;

    @Test
    void a_file_secret_rotated_between_two_locks_is_read_again_by_the_running_engine(@TempDir Path dir)
            throws Exception {
        secure(SECURED);
        publish(SECURED, "1.0", "1.1");
        Path token = dir.resolve("private-token");
        writeOwnerOnly(token, "first-token\n");
        userConfig(central() + """

                [repositories.private]
                url = "%s"
                groups = ["%s"]
                token = "${file:%s}"
                """.formatted(securedUrl(), GROUP, token));
        accepted.set("Bearer first-token");

        manifest(dir, "1.0");
        assertThat(run("lock", "-C", dir.toString())).isZero();

        Files.writeString(token, "rotated-token\n");
        Files.setLastModifiedTime(token, FileTime.from(Instant.now().plusSeconds(5)));
        accepted.set("Bearer rotated-token");
        manifest(dir, "1.1");
        assertThat(run("lock", "-C", dir.toString())).isZero();

        assertThat(presented).contains("Bearer first-token").endsWith("Bearer rotated-token");
    }

    @Test
    void a_helper_whose_token_is_refused_is_run_once_more_and_the_request_retried(@TempDir Path dir) throws Exception {
        secure(SECURED);
        publish(SECURED, "1.0", "1.1");
        Path helper = countingHelper(dir);
        userConfig(central() + """

                [repositories.private]
                url = "%s"
                groups = ["%s"]
                token = "${cmd:%s}"
                """.formatted(securedUrl(), GROUP, helper));
        accepted.set("Bearer token-1");

        manifest(dir, "1.0");
        assertThat(run("lock", "-C", dir.toString())).isZero();
        assertThat(runs(helper)).as("one run serves the first lock").isEqualTo(1);

        accepted.set("Bearer token-2");
        manifest(dir, "1.1");
        assertThat(run("lock", "-C", dir.toString())).isZero();

        assertThat(runs(helper)).as("the refusal re-ran the helper once").isEqualTo(2);
        assertThat(presented).contains("Bearer token-1").endsWith("Bearer token-2");
    }

    @Test
    void a_helper_token_on_a_native_mirror_authenticates_every_request_and_is_refreshed_on_refusal(@TempDir Path dir)
            throws Exception {
        secure("");
        publish("", "1.0", "1.1");
        Path helper = countingHelper(dir);
        userConfig("""
                [repositories.corp]
                url = "%s"
                mirror-of = ["*"]
                token = "${cmd:%s}"
                """.formatted(http.baseUrl(), helper));
        accepted.set("Bearer token-1");

        manifest(dir, "1.0");
        assertThat(run("lock", "-C", dir.toString())).isZero();
        assertThat(presented).isNotEmpty().allMatch("Bearer token-1"::equals);

        accepted.set("Bearer token-2");
        presented.clear();
        manifest(dir, "1.1");
        assertThat(run("lock", "-C", dir.toString())).isZero();

        assertThat(runs(helper)).isEqualTo(2);
        assertThat(presented).endsWith("Bearer token-2");
    }

    /**
     * Answer every path under {@code prefix} only for the {@link #accepted} token; record what each
     * request presented. An empty prefix secures the whole server.
     */
    private void secure(String prefix) {
        http.server().createContext(http.base().getPath() + prefix, exchange -> {
            String path = exchange.getRequestURI()
                    .getPath()
                    .substring(http.base().getPath().length());
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            // Only the resolve's own requests: a fresh engine's calibration probe also times a GET
            // through the mirror, anonymously, and is refused like any stranger.
            if (path.contains("/" + GROUP.replace('.', '/') + "/") || path.contains("/org/junit/")) {
                presented.add(auth == null ? "<anonymous>" : auth);
            }
            if (!accepted.get().equals(auth)) {
                exchange.sendResponseHeaders(401, -1);
            } else {
                serve(exchange, path);
            }
            exchange.close();
        });
    }

    private void serve(HttpExchange exchange, String path) throws IOException {
        byte[] body = http.served().get(path);
        if (body == null) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    /** {@code GROUP:ARTIFACT} at each version under {@code prefix}, plus the JUnit defaults at the root. */
    private void publish(String prefix, String... versions) {
        MavenStub stub = new MavenStub(http)
                .leaf("org.junit.jupiter", "junit-jupiter", DefaultTestDepsFixture.JUNIT_VERSION)
                .leaf("org.junit.platform", "junit-platform-launcher", DefaultTestDepsFixture.JUNIT_VERSION);
        for (String v : versions) stub.leaf(GROUP, ARTIFACT, v);
        stub.metadata(GROUP, ARTIFACT, versions);
        if (prefix.isEmpty()) return;
        String root = "/" + GROUP.replace('.', '/') + "/";
        for (String path : List.copyOf(http.served().keySet())) {
            if (path.startsWith(root))
                http.served().put(prefix + path, http.served().remove(path));
        }
    }

    private String securedUrl() {
        return http.baseUrl() + SECURED.substring(1) + "/";
    }

    private String central() {
        return """
                [repositories.central]
                url = "%s"
                """.formatted(http.baseUrl());
    }

    /** Pinned exactly: a bare version is exact, and both phases' versions are listed. */
    private static void manifest(Path dir, String version) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "secrets"
                version = "0.1.0"
                java = 25

                [dependencies]
                lib = { group = "%s", name = "%s", version = "%s" }
                """.formatted(GROUP, ARTIFACT, version));
    }

    /** A helper that prints {@code token-<n>}, n counting its runs in a file beside it. */
    private static Path countingHelper(Path dir) throws IOException {
        Path helper = dir.resolve("token-helper.sh");
        Files.writeString(helper, """
                #!/bin/sh
                count="$0.count"
                n=$(cat "$count" 2>/dev/null || echo 0)
                n=$((n + 1))
                echo "$n" > "$count"
                echo "token-$n"
                """);
        Files.setPosixFilePermissions(helper, PosixFilePermissions.fromString("rwx------"));
        return helper;
    }

    private static int runs(Path helper) throws IOException {
        Path count = helper.resolveSibling(helper.getFileName() + ".count");
        return Files.exists(count) ? Integer.parseInt(Files.readString(count).strip()) : 0;
    }

    private static void writeOwnerOnly(Path file, String body) throws IOException {
        Files.writeString(file, body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }

    /** The isolated home's {@code config.toml}: its starting text, then {@code tables}. */
    private void userConfig(String tables) throws IOException {
        Path config = JkDirs.userConfigFile();
        if (homeConfig == null) homeConfig = Files.readString(config);
        Files.writeString(config, homeConfig + "\n[network]\nproxy = \"" + proxy.url() + "\"\n\n" + tables);
    }
}
