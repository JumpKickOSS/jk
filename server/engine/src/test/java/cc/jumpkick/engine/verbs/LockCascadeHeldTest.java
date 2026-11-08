// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.runtime.workspace.ManifestUpdates;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.LockFinishEvent;
import cc.jumpkick.wire.protocol.UpdateRewriteEvent;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** An unaccepted update holds every move across a minor or a major and names them in a run notice. */
class LockCascadeHeldTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @TempDir
    Path store;

    @BeforeEach
    void isolate() {
        System.setProperty("jk.env.JK_STORE_DIR", store.resolve("store").toString());
        RunNotices.clear();
    }

    @AfterEach
    void release() {
        RunNotices.clear();
    }

    @Test
    void the_notice_names_each_held_move_and_the_command_that_takes_them() {
        Path dir = Path.of("/p");
        ManifestUpdates.Plan plan = new ManifestUpdates.Plan(
                List.of(rewrite(dir, "jackson", "2.18.0", "2.18.2")),
                Map.of(),
                List.of(rewrite(dir, "other", "1.0.0", "1.1.0"), rewrite(dir, "guava", "33.0.0", "34.0.0")));

        assertThat(LockCascade.held(plan))
                .isEqualTo("held 2 pins across a minor or a major (jk update -y takes them):"
                        + " other 1.0.0 → 1.1.0, guava 33.0.0 → 34.0.0");
        assertThat(LockCascade.held(new ManifestUpdates.Plan(List.of(rewrite(dir, "a", "1.0.0", "1.0.1")), Map.of())))
                .isNull();
    }

    @Test
    void when_every_move_is_held_nothing_is_written_or_relocked_and_the_run_succeeds(@TempDir Path project)
            throws Exception {
        MavenStub upstream = new MavenStub(http);
        for (String v : List.of("1.0.0", "1.1.0")) {
            upstream.pom("com.acme", "other", v, MavenStub.emptyPom("com.acme", "other", v));
        }
        upstream.metadata("com.acme", "other", "1.0.0", "1.1.0");
        String manifest = """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [dependencies]
                other = "com.acme:other:1.0.0"
                """;
        Files.writeString(project.resolve("jk.toml"), manifest);
        RecordingHost host = new RecordingHost();
        List<String> notices = new ArrayList<>();
        RunNotices.openSink(SessionContext.current().io(), (code, message) -> notices.add(message));

        JobOutcome outcome = LockCascade.run(
                host,
                project,
                project.resolve("cache"),
                http.base(),
                List.of(),
                true,
                new LockMode.Update(null),
                false,
                ManifestUpdates.Selection.ALL,
                false,
                null);

        assertThat(outcome).isEqualTo(JobOutcome.ok());
        assertThat(Files.readString(project.resolve("jk.toml"))).isEqualTo(manifest);
        assertThat(project.resolve("jk-lock.toml")).doesNotExist();
        assertThat(rewrites(host))
                .extracting(e -> e.handle(), e -> e.from(), e -> e.to(), e -> e.held())
                .containsExactly(tuple("other", "1.0.0", "1.1.0", true));
        assertThat(host.sent).noneMatch(l -> EngineProtocol.LOCK_MODULE.equals(EngineProtocol.typeOf(l)));
        LockFinishEvent finish = LockFinishEvent.decode(host.sent.getLast());
        assertThat(finish.success()).isTrue();
        assertThat(finish.exitCode()).isZero();
        assertThat(notices)
                .containsExactly("held 1 pin across a minor or a major (jk update -y takes them): other 1.0.0 → 1.1.0");
    }

    @Test
    void a_pin_that_takes_a_patch_is_held_from_the_patch_in_one_pass(@TempDir Path project) throws Exception {
        MavenStub upstream = new MavenStub(http);
        for (String v : List.of("1.0.0", "1.0.1", "1.1.0")) {
            upstream.pom("com.acme", "other", v, MavenStub.emptyPom("com.acme", "other", v));
        }
        upstream.metadata("com.acme", "other", "1.0.0", "1.0.1", "1.1.0");
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        Files.writeString(project.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [dependencies]
                other = "com.acme:other:1.0.0"
                """);
        RecordingHost host = new RecordingHost();
        List<String> notices = new ArrayList<>();
        RunNotices.openSink(SessionContext.current().io(), (code, message) -> notices.add(message));

        JobOutcome outcome = LockCascade.run(
                host,
                project,
                project.resolve("cache"),
                http.base(),
                List.of(),
                true,
                new LockMode.Update(null),
                false,
                ManifestUpdates.Selection.ALL,
                false,
                null);

        assertThat(outcome).as(String.join("\n", host.sent)).isEqualTo(JobOutcome.ok());
        assertThat(Files.readString(project.resolve("jk.toml"))).contains("com.acme:other:1.0.1");
        assertThat(rewrites(host))
                .extracting(e -> e.handle(), e -> e.from(), e -> e.to(), e -> e.held())
                .as("the patch is taken and the minor held from where the pin lands")
                .containsExactly(tuple("other", "1.0.0", "1.0.1", false), tuple("other", "1.0.1", "1.1.0", true));
        assertThat(notices)
                .containsExactly("held 1 pin across a minor or a major (jk update -y takes them): other 1.0.1 → 1.1.0");
    }

    private static List<UpdateRewriteEvent> rewrites(RecordingHost host) {
        return host.sent.stream()
                .filter(l -> EngineProtocol.UPDATE_REWRITE.equals(EngineProtocol.typeOf(l)))
                .map(UpdateRewriteEvent::decode)
                .toList();
    }

    private static ManifestUpdates.Rewrite rewrite(Path dir, String handle, String from, String to) {
        return new ManifestUpdates.Rewrite(
                dir.resolve("jk.toml"), dir, "", "dependencies", handle, "com.acme:" + handle, from, to);
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
