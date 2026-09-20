// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.runtime.workspace.OutdatedPlans;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.LockRequest;
import cc.jumpkick.wire.protocol.OutdatedReport;
import cc.jumpkick.wire.protocol.UpdateRequest;
import cc.jumpkick.wire.protocol.UpdateRewriteEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Engine-hosted {@code jk update} over the socket: a preview reports the pin moves without
 * writing; an update rewrites the selected exact pins in {@code jk.toml} and relocks; a keep-pins
 * or {@code -F} lock afterwards leaves the pins where the manifest says; {@code --major} crosses
 * the line; {@code jk outdated} reads the same repositories.
 */
@Tag("integration")
class UpdateRequestWireTest extends EngineServerHarness {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private @Nullable String previousM2;

    @BeforeEach
    void isolate() throws IOException {
        previousM2 = System.getProperty("jk.m2.local");
        System.setProperty("jk.m2.local", shortTempDir().toString()); // never touch the real ~/.m2
        System.setProperty(
                "jk.env.JK_STORE_DIR", shortTempDir().resolve("store").toString());
    }

    @AfterEach
    void release() {
        if (previousM2 != null) System.setProperty("jk.m2.local", previousM2);
        else System.clearProperty("jk.m2.local");
        System.clearProperty("jk.env.JK_STORE_DIR");
        LockfileReader.clearCache();
    }

    @Test
    void update_rewrites_the_selected_pins_and_relocks_while_lock_keeps_them() throws Exception {
        MavenStub upstream = new MavenStub(http);
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        publish(upstream, "jackson", "2.18.0", "2.18.2", "3.0.0");
        publish(upstream, "other", "1.0.0", "1.1.0");

        Path project = shortTempDir();
        String manifest = """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                jdk     = 25
                java    = 25

                [dependencies]
                jackson = "com.acme:jackson:2.18.0"
                other = "com.acme:other:1.0.0"
                """;
        Files.writeString(project.resolve("jk.toml"), manifest);
        Path cache = shortTempDir();

        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = runInBackground(server);
        try {
            waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

            // Preview: every move reported, nothing written, no lock.
            Outcome preview = drive(p, update(project, cache, List.of(), false, true));
            assertThat(preview.rewrites)
                    .extracting(e -> e.handle(), e -> e.from(), e -> e.to())
                    .containsExactlyInAnyOrder(tuple("jackson", "2.18.0", "2.18.2"), tuple("other", "1.0.0", "1.1.0"));
            assertThat(preview.rewrites).allSatisfy(e -> {
                assertThat(e.dir()).isEqualTo(project.toString());
                assertThat(e.table()).isEqualTo("dependencies");
            });
            assertThat(preview.success()).isTrue();
            assertThat(Files.readString(project.resolve("jk.toml"))).isEqualTo(manifest);
            assertThat(project.resolve("jk-lock.toml")).doesNotExist();

            // Update one handle: its pin moves in jk.toml and the relock follows; the other exact
            // pin stays where it is written even though 1.1.0 is published.
            Outcome moved = drive(p, update(project, cache, List.of("jackson"), false, false));
            assertThat(moved.success()).isTrue();
            assertThat(moved.rewrites)
                    .extracting(e -> e.handle(), e -> e.to())
                    .containsExactly(tuple("jackson", "2.18.2"));
            assertThat(Files.readString(project.resolve("jk.toml")))
                    .contains("jackson = \"com.acme:jackson:2.18.2\"")
                    .contains("other = \"com.acme:other:1.0.0\"");
            assertThat(moved.planFinish).isNotNull();
            assertThat(Jsonl.bool(moved.planFinish, "success", false)).isTrue();
            assertThat(locked(project, "com.acme:jackson")).isEqualTo("2.18.2");
            assertThat(locked(project, "com.acme:other")).isEqualTo("1.0.0");

            // jk outdated: an exact pin's Compatible is the pin itself; Latest is the newest stable.
            OutdatedReport outdated = OutdatedPlans.compute(project, cache, http.base(), OutdatedPlans.Progress.NONE);
            assertThat(outdated.rows())
                    .filteredOn(r -> r.coordinate().equals("com.acme:jackson"))
                    .extracting(r -> r.current(), r -> r.compatible(), r -> r.latest())
                    .containsExactly(tuple("2.18.2", "2.18.2", "3.0.0"));

            // A keep-pins lock and a -F lock both leave exact pins alone.
            assertThat(drive(p, lock(project, cache, false)).success()).isTrue();
            assertThat(locked(project, "com.acme:jackson")).isEqualTo("2.18.2");
            assertThat(locked(project, "com.acme:other")).isEqualTo("1.0.0");
            assertThat(drive(p, lock(project, cache, true)).success()).isTrue();
            assertThat(locked(project, "com.acme:jackson")).isEqualTo("2.18.2");
            assertThat(locked(project, "com.acme:other")).isEqualTo("1.0.0");
            assertThat(Files.readString(project.resolve("jk.toml"))).contains("com.acme:jackson:2.18.2");

            // --major crosses the line for every pin.
            Outcome crossed = drive(p, update(project, cache, List.of(), true, false));
            assertThat(crossed.success()).isTrue();
            assertThat(crossed.rewrites)
                    .extracting(e -> e.handle(), e -> e.from(), e -> e.to())
                    .containsExactlyInAnyOrder(tuple("jackson", "2.18.2", "3.0.0"), tuple("other", "1.0.0", "1.1.0"));
            assertThat(Files.readString(project.resolve("jk.toml")))
                    .contains("jackson = \"com.acme:jackson:3.0.0\"")
                    .contains("other = \"com.acme:other:1.1.0\"");
            assertThat(locked(project, "com.acme:jackson")).isEqualTo("3.0.0");
            assertThat(locked(project, "com.acme:other")).isEqualTo("1.1.0");
        } finally {
            server.close();
            serverThread.join(5_000);
        }
    }

    // ---- wire ------------------------------------------------------------------

    private String update(Path project, Path cache, List<String> deps, boolean major, boolean preview) {
        return new UpdateRequest(
                        project.toString(),
                        cache.toString(),
                        List.of(),
                        false,
                        http.base().toString(),
                        false,
                        null,
                        false,
                        false,
                        false,
                        "",
                        deps,
                        major,
                        preview)
                .encode();
    }

    private String lock(Path project, Path cache, boolean force) {
        return new LockRequest(
                        project.toString(),
                        cache.toString(),
                        List.of(),
                        false,
                        false,
                        http.base().toString(),
                        false,
                        force,
                        false,
                        false)
                .encode();
    }

    /** Everything a lock/update conversation ends with. */
    private record Outcome(
            List<UpdateRewriteEvent> rewrites, @Nullable String planFinish, String lockFinish) {
        boolean success() {
            return Jsonl.bool(lockFinish, "success", false) && Jsonl.intValue(lockFinish, "exitCode", -1) == 0;
        }
    }

    /** Drive one request to its {@code lock-finish}, collecting the pin moves it streamed. */
    private static Outcome drive(EnginePaths.Paths p, String request) throws IOException {
        List<UpdateRewriteEvent> rewrites = new ArrayList<>();
        String planFinish = null;
        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            c.sendLine(request);
            String line;
            while ((line = c.readLine()) != null) {
                String type = requireNonNull(EngineProtocol.typeOf(line), line);
                switch (type) {
                    case EngineProtocol.UPDATE_REWRITE -> rewrites.add(UpdateRewriteEvent.decode(line));
                    case EngineProtocol.BUILDPLAN_FINISH -> planFinish = line;
                    case EngineProtocol.LOCK_FINISH -> {
                        return new Outcome(rewrites, planFinish, line);
                    }
                    case EngineProtocol.ERROR -> throw new IOException("request failed: " + line);
                    default -> {
                        /* plan/progress events */
                    }
                }
            }
        }
        throw new IOException("disconnected before lock-finish");
    }

    private static String locked(Path project, String module) throws IOException {
        LockfileReader.clearCache();
        Lockfile lock = LockfileReader.read(project.resolve("jk-lock.toml"));
        return lock.artifacts().stream()
                .filter(a -> a.matchesModule(module))
                .map(Lockfile.Artifact::version)
                .findFirst()
                .orElseThrow(() -> new AssertionError(module + " not in the lock"));
    }

    private static void publish(MavenStub upstream, String artifact, String... versions) {
        for (String v : versions) {
            upstream.pom("com.acme", artifact, v, MavenStub.emptyPom("com.acme", artifact, v));
        }
        upstream.metadata("com.acme", artifact, versions);
    }
}
