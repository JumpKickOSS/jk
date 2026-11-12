// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Request;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.task.IoLedger;
import cc.jumpkick.task.RunNotices;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code ${cmd:...}} credential in {@code ~/.jk/config.toml}, end to end through the group a
 * resolve builds: a stub that accepts one token at a time, and a helper that prints the next token
 * each time it runs.
 */
@Tag("integration")
class RepoCredentialHelperGroupTest {

    private HttpServer server;
    private final AtomicReference<String> accepted = new AtomicReference<>("token-1");
    private final List<String> authorizations = Collections.synchronizedList(new ArrayList<>());

    /** The test's own local repository: a POM cached in the tier's shared one would answer for the stub. */
    @TempDir
    Path m2;

    @BeforeEach
    void start() throws IOException {
        System.setProperty("jk.m2.local", m2.toString());
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        RunNotices.clear();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/maven/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(String.valueOf(auth));
            byte[] body = null;
            int status;
            if (!("Bearer " + accepted.get()).equals(auth)) {
                status = 401;
            } else {
                body = served(exchange.getRequestURI().getPath());
                status = body == null ? 404 : 200;
            }
            exchange.sendResponseHeaders(status, body == null ? -1 : body.length);
            if (body != null) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        RunNotices.clear();
    }

    /** A POM for any {@code com/acme/<artifact>/1.0/<artifact>-1.0.pom}, and its {@code .sha1}. */
    private static byte @Nullable [] served(String path) {
        String rel = path.substring("/maven/".length());
        String[] parts = rel.split("/");
        if (parts.length != 5 || !parts[0].equals("com") || !parts[1].equals("acme")) return null;
        String artifact = parts[2];
        byte[] pom = ("<project><groupId>com.acme</groupId><artifactId>" + artifact
                        + "</artifactId><version>1.0</version></project>")
                .getBytes(StandardCharsets.UTF_8);
        if (parts[4].equals(artifact + "-1.0.pom")) return pom;
        if (parts[4].equals(artifact + "-1.0.pom.sha1")) {
            return Hashing.hashHex("SHA-1", pom).getBytes(StandardCharsets.UTF_8);
        }
        return null;
    }

    /** A helper that prints {@code token-<n>} on its n-th run, counting runs in {@code dir/count}. */
    private static Path helper(Path dir) throws IOException {
        Path script = dir.resolve("helper.sh");
        Files.writeString(script, """
                #!/bin/sh
                n=$(cat "%1$s/count" 2>/dev/null || echo 0)
                n=$((n + 1))
                echo "$n" > "%1$s/count"
                echo "token-$n"
                """.formatted(dir));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    private static int runs(Path dir) throws IOException {
        Path count = dir.resolve("count");
        return Files.exists(count) ? Integer.parseInt(Files.readString(count).strip()) : 0;
    }

    /** {@code ~/.jk/config.toml} declaring {@code gar} at the stub, its token from the helper. */
    private void userConfig(Path home, Path helper) throws IOException {
        Files.createDirectories(home);
        Files.writeString(
                home.resolve("config.toml"), """
                [repositories.gar]
                url = "http://127.0.0.1:%d/maven/"
                groups = ["com.acme"]
                token = "${cmd:%s}"
                """.formatted(server.getAddress().getPort(), helper));
        System.setProperty("jk.env.JK_HOME", home.toString());
    }

    private static MavenRepo gar(RepoGroup group) {
        return group.repos().stream()
                .filter(r -> r.name().equals("gar"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void a_token_the_stub_stops_accepting_reruns_the_helper_once_and_the_resolve_succeeds(@TempDir Path tmp)
            throws Exception {
        Path helper = helper(tmp);
        userConfig(tmp.resolve("home"), helper);
        Cas cas = new Cas(tmp.resolve("cas"));

        gar(RepoGroupBuilder.buildDefault(cas)).fetchPom(Coordinate.of("com.acme", "first", "1.0"));
        assertThat(runs(tmp)).isEqualTo(1);

        accepted.set("token-2");
        gar(RepoGroupBuilder.buildDefault(cas)).fetchPom(Coordinate.of("com.acme", "second", "1.0"));

        assertThat(runs(tmp)).isEqualTo(2);
        assertThat(authorizations).contains("Bearer token-1", "Bearer token-2");
    }

    @Test
    void a_mirror_whose_helper_token_the_stub_stops_accepting_reruns_the_helper_once(@TempDir Path tmp)
            throws Exception {
        Path helper = helper(tmp);
        Path home = tmp.resolve("home");
        Files.createDirectories(home);
        Files.writeString(
                home.resolve("config.toml"), """
                [repositories.corp]
                url = "http://127.0.0.1:%d/maven/"
                mirror-of = ["central"]
                token = "${cmd:%s}"
                """.formatted(server.getAddress().getPort(), helper));
        System.setProperty("jk.env.JK_HOME", home.toString());
        Cas cas = new Cas(tmp.resolve("cas"));

        central(RepoGroupBuilder.buildDefault(cas)).fetchPom(Coordinate.of("com.acme", "first", "1.0"));
        assertThat(runs(tmp)).isEqualTo(1);

        accepted.set("token-2");
        central(RepoGroupBuilder.buildDefault(cas)).fetchPom(Coordinate.of("com.acme", "second", "1.0"));

        assertThat(runs(tmp)).isEqualTo(2);
        assertThat(authorizations).contains("Bearer token-1", "Bearer token-2");
    }

    private static MavenRepo central(RepoGroup group) {
        return group.repos().stream()
                .filter(r -> r.name().equals("central"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void a_helper_whose_tokens_are_always_refused_runs_exactly_twice_and_the_failure_names_the_repository(
            @TempDir Path tmp) throws Exception {
        Path helper = helper(tmp);
        userConfig(tmp.resolve("home"), helper);
        accepted.set("never-issued");
        MavenRepo gar = gar(RepoGroupBuilder.buildDefault(new Cas(tmp.resolve("cas"))));

        // A loopback repository is passable, so the refusal puts it out of the job, naming it.
        assertThatThrownBy(() -> gar.fetchPom(Coordinate.of("com.acme", "lib", "1.0")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("gar")
                .hasMessageContaining("401")
                .hasMessageContaining("the credential helper's new value was refused too");
        assertThat(runs(tmp)).isEqualTo(2);
        assertThat(gar.unreachableFault())
                .as("the passed-over note carries the helper's side too")
                .contains("was refused too");
    }

    @Test
    void a_three_module_lock_with_an_always_refused_helper_runs_it_exactly_twice(@TempDir Path tmp) throws Exception {
        Path helper = helper(tmp);
        userConfig(tmp.resolve("home"), helper);
        accepted.set("never-issued");
        Cas cas = new Cas(tmp.resolve("cas"));
        List<Throwable> failures = new ArrayList<>();
        // One request, as a lock is: each module builds its own group, so each has its own gar.
        Request.of(1, new IoLedger()).run(() -> {
            try {
                for (String module : List.of("api", "core", "app")) {
                    Path dir = Files.createDirectories(tmp.resolve("ws").resolve(module));
                    Path manifest = Files.writeString(dir.resolve("jk.toml"), """
                            group = "com.acme"
                            name = "%s"
                            version = "1.0"
                            """.formatted(module));
                    RepoGroup group = RepoGroupBuilder.buildFor(
                            JkBuildParser.parse(manifest), null, cas, BuildEnv.forModule(dir));
                    try {
                        gar(group).fetchPom(Coordinate.of("com.acme", module + "-dep", "1.0"));
                    } catch (IOException e) {
                        failures.add(e);
                    }
                }
            } finally {
                RequestScope.release();
            }
        });

        assertThat(runs(tmp)).isEqualTo(2);
        assertThat(failures).hasSize(3).allSatisfy(e -> assertThat(e).hasMessageContaining("gar"));
        assertThat(failures.getLast())
                .cause()
                .hasMessageContaining("the credential helper's new value was refused too");
        String rendered = String.valueOf(failures.getLast().getMessage());
        assertThat(rendered.split("the credential helper's new value was refused too", -1))
                .as("the refresher's explanation is in the failure's own message, once: %s", rendered)
                .hasSize(2);
    }

    @Test
    void fifty_parallel_fetches_refused_together_run_the_helper_once_more(@TempDir Path tmp) throws Exception {
        Path helper = helper(tmp);
        userConfig(tmp.resolve("home"), helper);
        MavenRepo gar = gar(RepoGroupBuilder.buildDefault(new Cas(tmp.resolve("cas"))));
        assertThat(runs(tmp)).isEqualTo(1);
        accepted.set("token-2");

        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 50; i++) {
            String artifact = "lib" + i;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    gar.fetchPom(Coordinate.of("com.acme", artifact, "1.0"));
                } catch (Exception e) {
                    failures.add(e);
                }
            }));
        }
        for (Thread t : threads) t.join(30_000);

        assertThat(failures).isEmpty();
        assertThat(runs(tmp)).isEqualTo(2);
    }
}
