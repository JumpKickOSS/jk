// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.Scope;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.runtime.workspace.AddedPackages;
import cc.jumpkick.runtime.workspace.ManifestUpdates;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.wire.protocol.EngineProtocol;
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

/**
 * An update asks nobody: it takes every move on a pin's major, across a minor and with the packages
 * its relock adds, and names each release past a pin's major as available, untaken.
 */
class LockCascadeUpdateTest {

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
    void the_notice_names_each_newer_major_and_the_flag_that_takes_them() {
        Path dir = Path.of("/p");

        assertThat(LockCascade.available(
                        List.of(rewrite(dir, "other", "1.1.0", "2.0.0"), rewrite(dir, "guava", "33.0.0", "34.0.0"))))
                .isEqualTo("2 pins have a newer major (jk update --major takes them):"
                        + " other 1.1.0 → 2.0.0, guava 33.0.0 → 34.0.0");
        assertThat(LockCascade.available(List.of())).isNull();
    }

    /**
     * A move across a minor and a move whose relock adds a package both apply, the lock follows,
     * and a release past a pin's major is named, in an available event and a run notice, and left.
     */
    @Test
    void every_move_on_the_major_applies_and_a_newer_major_is_reported_not_taken(@TempDir Path project)
            throws Exception {
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
        for (String v : List.of("1.0.0", "1.1.0", "2.0.0")) {
            upstream.pom("com.acme", "other", v, MavenStub.emptyPom("com.acme", "other", v));
        }
        upstream.metadata("com.acme", "other", "1.0.0", "1.1.0", "2.0.0");
        Path manifest = project.resolve("jk.toml");
        Files.writeString(manifest, """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [dependencies]
                growing = "com.acme:growing:1.0.0"
                other = "com.acme:other:1.0.0"
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
        Path lock = project.resolve("jk-lock.toml");
        assertThat(Files.readString(lock)).doesNotContain("com.acme:extra");
        List<String> notices = new ArrayList<>();
        RunNotices.openSink(SessionContext.current().io(), (code, message) -> notices.add(message));

        RecordingHost host = new RecordingHost();
        assertThat(update(host, project)).as(String.join("\n", host.sent)).isEqualTo(JobOutcome.ok());

        assertThat(Files.readString(manifest))
                .contains("com.acme:growing:1.0.1")
                .contains("com.acme:other:1.1.0");
        assertThat(Files.readString(lock)).contains("com.acme:extra");
        assertThat(locked(lock, "com.acme:growing")).isEqualTo("1.0.1");
        assertThat(locked(lock, "com.acme:other")).isEqualTo("1.1.0");
        assertThat(rewrites(host))
                .extracting(e -> e.handle(), e -> e.from(), e -> e.to(), e -> e.available())
                .containsExactlyInAnyOrder(
                        tuple("growing", "1.0.0", "1.0.1", false),
                        tuple("other", "1.0.0", "1.1.0", false),
                        tuple("other", "1.1.0", "2.0.0", true));
        assertThat(notices)
                .containsExactly("1 pin has a newer major (jk update --major takes it): other 1.1.0 → 2.0.0");
    }

    @Test
    void the_packages_a_move_adds_are_those_its_rows_reach() {
        Path dir = Path.of("/p");
        Lockfile before = lock(row("com.acme:a", "1.0.0"), row("com.acme:b", "1.0.0"));
        Lockfile after = lock(
                row("com.acme:a", "1.0.1", "com.acme:new-a:jar:@1.0"),
                row("com.acme:new-a", "1.0", "com.acme:deeper:jar:@2.0"),
                row("com.acme:deeper", "2.0"),
                row("com.acme:b", "1.0.1"));
        ManifestUpdates.Rewrite a = rewrite(dir, "a", "1.0.0", "1.0.1");
        ManifestUpdates.Rewrite b = rewrite(dir, "b", "1.0.0", "1.0.1");

        assertThat(AddedPackages.byMove(before, after, List.of(a, b)))
                .containsExactly(Map.entry(a, List.of("com.acme:new-a 1.0", "com.acme:deeper 2.0")));
        assertThat(AddedPackages.byMove(null, after, List.of(a, b)))
                .as("a first lock adds every package")
                .isEmpty();

        Lockfile stray = lock(row("com.acme:a", "1.0.1"), row("com.acme:b", "1.0.1"), row("com.acme:stray", "1.0"));
        assertThat(AddedPackages.byMove(before, stray, List.of(a, b)))
                .as("a package no move reaches is named on every move")
                .containsExactly(
                        Map.entry(a, List.of("com.acme:stray 1.0")), Map.entry(b, List.of("com.acme:stray 1.0")));
    }

    private JobOutcome update(RecordingHost host, Path project) throws Exception {
        return LockCascade.run(
                host,
                project,
                project.resolve("cache"),
                http.base(),
                List.of(),
                true,
                new LockMode.Update(null),
                false,
                ManifestUpdates.Selection.ALL,
                null);
    }

    private static List<UpdateRewriteEvent> rewrites(RecordingHost host) {
        return host.sent.stream()
                .filter(l -> EngineProtocol.UPDATE_REWRITE.equals(EngineProtocol.typeOf(l)))
                .map(UpdateRewriteEvent::decode)
                .toList();
    }

    private static String locked(Path lock, String ga) throws Exception {
        return LockfileReader.read(lock).artifacts().stream()
                .filter(r -> r.name().startsWith(ga + ":"))
                .map(Lockfile.Artifact::version)
                .findFirst()
                .orElseThrow();
    }

    private static Lockfile lock(Lockfile.Artifact... rows) {
        return new Lockfile(1, "test", "pubgrub", List.of(rows));
    }

    private static Lockfile.Artifact row(String module, String version, String... deps) {
        return new Lockfile.Artifact(
                module + ":jar:",
                version,
                "central",
                null,
                null,
                List.of(Scope.MAIN),
                List.of(deps),
                null,
                null,
                null,
                List.of(),
                List.of());
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
