// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.UpdateRequest;
import cc.jumpkick.wire.protocol.UpdateRewriteEvent;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk update}'s preview finds the packages a move adds by resolving the moved manifests from
 * memory: every manifest on disk holds its original bytes while that resolve runs, and it writes
 * nothing.
 */
class UpdateOverlayTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @TempDir
    Path store;

    @TempDir
    Path project;

    private final List<String> duringResolve = new CopyOnWriteArrayList<>();

    @BeforeEach
    void isolate() throws Exception {
        System.setProperty("jk.env.JK_STORE_DIR", store.resolve("store").toString());
        RunNotices.clear();
        MavenStub upstream = new MavenStub(http);
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        upstream.leaf("com.acme", "extra", "1.0");
        upstream.pom("com.acme", "growing", "1.0.0", MavenStub.emptyPom("com.acme", "growing", "1.0.0"));
        upstream.pom("com.acme", "growing", "1.0.1", """
                <project><groupId>com.acme</groupId><artifactId>growing</artifactId><version>1.0.1</version>
                  <dependencies><dependency>
                    <groupId>com.acme</groupId><artifactId>extra</artifactId><version>1.0</version>
                  </dependency></dependencies>
                </project>""");
        upstream.metadata("com.acme", "growing", "1.0.0", "1.0.1");
        Files.writeString(project.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [dependencies]
                growing = "com.acme:growing:1.0.0"
                """);
        assertThat(LockCascade.run(
                        new RecordingHost(),
                        project,
                        project.resolve("cache"),
                        http.base(),
                        List.of(),
                        true,
                        new LockMode.Keep(false),
                        false,
                        null))
                .isEqualTo(JobOutcome.ok());
    }

    @AfterEach
    void release() {
        RunNotices.clear();
    }

    @Test
    void a_preview_resolves_the_move_from_memory_and_writes_nothing() throws Exception {
        Snapshot before = Snapshot.of(project);
        watchDuringResolve(before);
        RecordingHost host = new RecordingHost();
        String request = new UpdateRequest(
                        project.toString(),
                        project.resolve("cache").toString(),
                        List.of(),
                        false,
                        http.base().toString(),
                        false,
                        null,
                        false,
                        false,
                        false,
                        "",
                        List.of(),
                        false,
                        true)
                .encode();

        JobOutcome outcome = new UpdateVerb(host).run(request, Session.CancelToken.NONE, null);

        assertThat(outcome).as(String.join("\n", host.sent)).isEqualTo(JobOutcome.ok());
        assertThat(duringResolve).isNotEmpty().allMatch("unchanged"::equals);
        assertThat(rewrites(host))
                .extracting(e -> e.handle(), e -> e.from(), e -> e.to(), e -> e.adds())
                .containsExactly(tuple("growing", "1.0.0", "1.0.1", List.of("com.acme:extra 1.0")));
        assertThat(Snapshot.of(project)).isEqualTo(before);
    }

    /** Records, each time the moved pin's POM is served, whether jk.toml still holds {@code before}'s bytes. */
    private void watchDuringResolve(Snapshot before) {
        http.beforeServe(path -> {
            if (!path.endsWith("growing-1.0.1.pom")) return;
            try {
                byte[] now = Files.readAllBytes(project.resolve("jk.toml"));
                duringResolve.add(Arrays.equals(now, before.manifest()) ? "unchanged" : new String(now));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** The manifest's and the lock's bytes and modification times. */
    private record Snapshot(byte[] manifest, FileTime manifestTime, String lock, FileTime lockTime) {

        static Snapshot of(Path project) throws IOException {
            Path manifest = project.resolve("jk.toml");
            Path lock = project.resolve("jk-lock.toml");
            return new Snapshot(
                    Files.readAllBytes(manifest),
                    Files.getLastModifiedTime(manifest),
                    Files.readString(lock),
                    Files.getLastModifiedTime(lock));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Snapshot s
                    && Arrays.equals(manifest, s.manifest)
                    && manifestTime.equals(s.manifestTime)
                    && lock.equals(s.lock)
                    && lockTime.equals(s.lockTime);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(manifest);
        }

        @Override
        public String toString() {
            return new String(manifest) + " @ " + manifestTime + "\n" + lock + " @ " + lockTime;
        }
    }

    private static List<UpdateRewriteEvent> rewrites(RecordingHost host) {
        return host.sent.stream()
                .filter(l -> EngineProtocol.UPDATE_REWRITE.equals(EngineProtocol.typeOf(l)))
                .map(UpdateRewriteEvent::decode)
                .toList();
    }

    private static final class RecordingHost extends InertVerbHost {
        final List<String> sent = new ArrayList<>();

        @Override
        public void sendQuiet(@Nullable BufferedWriter w, String line) {
            sent.add(line);
        }

        @Override
        public BuildPlanListener planListener(
                String dir, @Nullable BufferedWriter w, @Nullable Function<BuildPlanResult, String> enc) {
            return new BuildPlanListener() {
                @Override
                public void planFinish(BuildPlanResult result) {
                    if (enc != null) sent.add(enc.apply(result));
                }
            };
        }
    }
}
