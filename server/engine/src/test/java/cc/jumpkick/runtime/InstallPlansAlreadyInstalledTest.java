// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.InstallDestination;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.publish.PublishablePom;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallPlansAlreadyInstalledTest {

    private static JkBuild project(InstallDestination target) {
        return JkBuild.builder(Project.builder("cc.jumpkick", "jk-foo", "1.0")
                        .jdkMajor(25)
                        .java(25)
                        .installDestination(target)
                        .build())
                .build();
    }

    @Test
    void the_private_repository_is_skipped_when_jar_and_pom_match(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Path cache = tmp.resolve("cache");
        Path mod = tmp.resolve("mod");
        Files.createDirectories(mod);
        JkBuild project = project(InstallDestination.JK);
        BuildLayout layout = BuildLayout.of(mod, project);
        Files.createDirectories(layout.mainJar().getParent());
        Files.writeString(layout.mainJar(), "jar-bytes");

        String prev = System.getProperty("jk.env.JK_STORE_DIR");
        System.setProperty("jk.env.JK_STORE_DIR", store.toAbsolutePath().toString());
        try {
            assertThat(InstallPlans.alreadyInstalled(project, layout, cache, null, null))
                    .isFalse();

            Coordinate coord = Coordinate.of("cc.jumpkick", "jk-foo", "1.0");
            InstallPlans.writeToLocalStore(cache, MavenLayout.artifactPath(coord), layout.mainJar());
            Path pomFile = tmp.resolve("jk-foo.pom");
            Files.writeString(pomFile, PublishablePom.render(project, null).xml());
            InstallPlans.writeToLocalStore(cache, MavenLayout.pomPath(coord), pomFile);
            assertThat(InstallPlans.alreadyInstalled(project, layout, cache, null, null))
                    .isTrue();
            // The skipped step still reports the shas the target holds, for the client's pins.
            assertThat(InstallPlans.installedShas(project, layout, cache, null, null))
                    .isEqualTo(new ModuleOutcome.Installed(
                            coord.toGav(),
                            Hashing.sha256Hex(layout.mainJar()),
                            Hashing.sha256Hex(pomFile),
                            InstallDestination.JK,
                            ModuleOutcome.Installed.Change.UNCHANGED));

            Files.writeString(layout.mainJar(), "jar-bytes-changed");
            assertThat(InstallPlans.alreadyInstalled(project, layout, cache, null, null))
                    .isFalse();
            assertThat(InstallPlans.installedShas(project, layout, cache, null, null))
                    .isNull();
        } finally {
            if (prev != null) System.setProperty("jk.env.JK_STORE_DIR", prev);
            else System.clearProperty("jk.env.JK_STORE_DIR");
        }
    }

    /**
     * The Maven local target is checked in the same repo the install writes to — the
     * caller-resolved {@code --m2-dir} root, not the machine's — and the private shelf is not asked.
     */
    @Test
    void the_maven_local_target_is_checked_under_the_redirected_m2_dir(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Path cache = tmp.resolve("cache");
        Path mod = tmp.resolve("mod");
        Files.createDirectories(mod);
        JkBuild project = project(InstallDestination.M2);
        BuildLayout layout = BuildLayout.of(mod, project);
        Files.createDirectories(layout.mainJar().getParent());
        Files.writeString(layout.mainJar(), "jar-bytes");
        Coordinate coord = Coordinate.of("cc.jumpkick", "jk-foo", "1.0");
        Path pomFile = tmp.resolve("jk-foo.pom");
        Files.writeString(pomFile, PublishablePom.render(project, null).xml());

        String prevStore = System.getProperty("jk.env.JK_STORE_DIR");
        System.setProperty("jk.env.JK_STORE_DIR", store.toAbsolutePath().toString());
        try {
            // On the private shelf but not in the redirected repo: the install still has work to do.
            InstallPlans.writeToLocalStore(cache, MavenLayout.artifactPath(coord), layout.mainJar());
            InstallPlans.writeToLocalStore(cache, MavenLayout.pomPath(coord), pomFile);
            Path m2Dir = tmp.resolve("m2-redirected");
            assertThat(InstallPlans.alreadyInstalled(project, layout, cache, m2Dir, null))
                    .isFalse();

            Path repo = m2Dir.resolve("repository");
            Path m2Jar = repo.resolve(MavenLayout.artifactPath(coord));
            Path m2Pom = repo.resolve(MavenLayout.pomPath(coord));
            Files.createDirectories(m2Jar.getParent());
            Files.createDirectories(m2Pom.getParent());
            Files.copy(layout.mainJar(), m2Jar);
            Files.copy(pomFile, m2Pom);
            assertThat(InstallPlans.alreadyInstalled(project, layout, cache, m2Dir, null))
                    .isTrue();
            assertThat(InstallPlans.alreadyInstalled(project, layout, cache, tmp.resolve("other-m2"), null))
                    .as("a different --m2-dir has not been installed to")
                    .isFalse();

            // The forecast judges the same step against the same root: explain and build agree.
            assertThat(ForecastPackagingTails.cacheInstall(project, layout, cache, m2Dir, null, false)
                            .cached())
                    .as("the forecast reads the redirected repo the install wrote to")
                    .isTrue();
            assertThat(ForecastPackagingTails.cacheInstall(project, layout, cache, tmp.resolve("other-m2"), null, false)
                            .cached())
                    .as("the forecast under another --m2-dir predicts the install the build would run")
                    .isFalse();
            assertThat(ForecastPackagingTails.cacheInstall(project, layout, cache, m2Dir, null, true)
                            .cached())
                    .as("a jar this build rewrites is reinstalled whatever the repo holds")
                    .isFalse();
            assertThat(ForecastPackagingTails.cacheInstall(project, layout, cache, m2Dir, InstallDestination.JK, false)
                            .cached())
                    .as("--destination jk asks the private repo, which holds the bytes too")
                    .isTrue();
        } finally {
            if (prevStore != null) System.setProperty("jk.env.JK_STORE_DIR", prevStore);
            else System.clearProperty("jk.env.JK_STORE_DIR");
        }
    }

    @Test
    void the_private_repository_is_not_skipped_when_pom_bytes_differ(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Path cache = tmp.resolve("cache");
        Path mod = tmp.resolve("mod");
        Files.createDirectories(mod);
        JkBuild project = project(InstallDestination.JK);
        BuildLayout layout = BuildLayout.of(mod, project);
        Files.createDirectories(layout.mainJar().getParent());
        Files.writeString(layout.mainJar(), "jar-bytes");

        String prev = System.getProperty("jk.env.JK_STORE_DIR");
        System.setProperty("jk.env.JK_STORE_DIR", store.toAbsolutePath().toString());
        try {
            Coordinate coord = Coordinate.of("cc.jumpkick", "jk-foo", "1.0");
            InstallPlans.writeToLocalStore(cache, MavenLayout.artifactPath(coord), layout.mainJar());
            Path pomFile = tmp.resolve("jk-foo.pom");
            Files.write(pomFile, "<project/>".getBytes(StandardCharsets.UTF_8));
            InstallPlans.writeToLocalStore(cache, MavenLayout.pomPath(coord), pomFile);
            assertThat(InstallPlans.alreadyInstalled(project, layout, cache, null, null))
                    .isFalse();
        } finally {
            if (prev != null) System.setProperty("jk.env.JK_STORE_DIR", prev);
            else System.clearProperty("jk.env.JK_STORE_DIR");
        }
    }
}
