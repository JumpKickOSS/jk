// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.testing.FakeBuildIo;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import io.quarkus.paths.PathList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the augment does with a runtime artifact Quarkus's resolver found and the lock does not
 * name: it leaves it out of the application, and the user hears so on a build that passes — the
 * fork's notice reaches the step as a notice, not as output kept only when the fork fails.
 */
class LockedAppModelTest {

    private static ResolvedDependencyBuilder runtime(String group, String artifact, String version, Path jar) {
        return ResolvedDependencyBuilder.newInstance()
                .setGroupId(group)
                .setArtifactId(artifact)
                .setVersion(version)
                .setResolvedPaths(PathList.of(jar))
                .setFlags(DependencyFlags.RUNTIME_CP | DependencyFlags.DEPLOYMENT_CP | DependencyFlags.DIRECT);
    }

    private static ApplicationModel resolved(Path dir) throws Exception {
        Path appJar = Files.writeString(dir.resolve("app.jar"), "APP");
        ApplicationModelBuilder model = new ApplicationModelBuilder();
        model.setAppArtifact(ResolvedDependencyBuilder.newInstance()
                .setGroupId("com.example")
                .setArtifactId("app")
                .setVersion("1.0.0")
                .setResolvedPaths(PathList.of(appJar)));
        model.addDependency(runtime("io.quarkus", "quarkus-core", "3.40.1", dir.resolve("quarkus-core.jar")));
        // An extension's conditional runtime dependency: Quarkus resolves it, the lock never saw it.
        model.addDependency(runtime("io.smallrye", "smallrye-conditional", "2.1.0", dir.resolve("conditional.jar")));
        return model.build();
    }

    @Test
    void an_unlocked_runtime_artifact_is_left_out_and_its_notice_reaches_the_step(@TempDir Path dir) throws Exception {
        LockedClosure lock = LockedClosure.of(List.of(new LockedClosure.Artifact(
                "io.quarkus", "quarkus-core", "3.40.1", dir.resolve("store/quarkus-core-3.40.1.jar"), false)));
        Path notices = dir.resolve("notices.txt");

        ApplicationModel enforced = LockedAppModel.enforce(resolved(dir), lock, ForkNotices.to(notices));

        assertThat(enforced.getRuntimeDependencies())
                .extracting(ResolvedDependency::getArtifactId)
                .containsExactly("quarkus-core");

        FakeBuildIo step = new FakeBuildIo(dir.resolve("step"), "quarkus");
        ForkNotices.relay(notices, step);

        assertThat(step.diagnostics()).singleElement().satisfies(notice -> assertThat(notice)
                .startsWith("notice: NOT shipped — outside jk-lock.toml")
                .contains("io.smallrye:smallrye-conditional:2.1.0")
                .doesNotContain("quarkus-core"));
    }

    @Test
    void a_closure_the_lock_covers_says_nothing(@TempDir Path dir) throws Exception {
        LockedClosure lock = LockedClosure.of(List.of(
                new LockedClosure.Artifact("io.quarkus", "quarkus-core", "3.40.1", dir.resolve("core.jar"), false),
                new LockedClosure.Artifact(
                        "io.smallrye", "smallrye-conditional", "2.1.0", dir.resolve("conditional.jar"), false)));
        Path notices = dir.resolve("notices.txt");

        LockedAppModel.enforce(resolved(dir), lock, ForkNotices.to(notices));

        FakeBuildIo step = new FakeBuildIo(dir.resolve("step"), "quarkus");
        ForkNotices.relay(notices, step);
        assertThat(notices).doesNotExist();
        assertThat(step.diagnostics()).isEmpty();
    }

    @Test
    void a_fork_named_no_notices_file_only_prints(@TempDir Path dir) throws Exception {
        assertThat(System.getProperty(ForkNotices.PROPERTY)).isNull();
        ForkNotices.fromProperty().say("jk-quarkus-augment", "NOT shipped — outside jk-lock.toml: a:b:1");
        try (var listing = Files.list(dir)) {
            assertThat(listing).isEmpty();
        }
    }
}
