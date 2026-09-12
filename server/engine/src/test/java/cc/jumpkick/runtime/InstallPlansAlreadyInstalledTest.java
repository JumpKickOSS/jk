// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.publish.PublishablePom;
import cc.jumpkick.repo.MavenLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallPlansAlreadyInstalledTest {

    @Test
    void skip_when_jar_and_pom_match(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Path cache = tmp.resolve("cache");
        Path mod = tmp.resolve("mod");
        Files.createDirectories(mod);
        JkBuild project = JkBuild.builder(Project.builder("cc.jumpkick", "jk-foo", "1.0")
                        .jdkMajor(25)
                        .java(25)
                        .m2install(false)
                        .build())
                .build();
        BuildLayout layout = BuildLayout.of(mod, project);
        Files.createDirectories(layout.mainJar().getParent());
        Files.writeString(layout.mainJar(), "jar-bytes");

        String prev = System.getProperty("jk.env.JK_STORE_DIR");
        System.setProperty("jk.env.JK_STORE_DIR", store.toAbsolutePath().toString());
        try {
            assertThat(InstallPlans.alreadyInstalled(project, layout, cache)).isFalse();

            Coordinate coord = Coordinate.of("cc.jumpkick", "jk-foo", "1.0");
            InstallPlans.writeToLocalStore(cache, MavenLayout.artifactPath(coord), layout.mainJar());
            Path pomFile = tmp.resolve("jk-foo.pom");
            Files.writeString(pomFile, PublishablePom.render(project, null).xml());
            InstallPlans.writeToLocalStore(cache, MavenLayout.pomPath(coord), pomFile);
            assertThat(InstallPlans.alreadyInstalled(project, layout, cache)).isTrue();

            Files.writeString(layout.mainJar(), "jar-bytes-changed");
            assertThat(InstallPlans.alreadyInstalled(project, layout, cache)).isFalse();
        } finally {
            if (prev != null) System.setProperty("jk.env.JK_STORE_DIR", prev);
            else System.clearProperty("jk.env.JK_STORE_DIR");
        }
    }

    /**
     * With {@code [m2] install} on, the check looks in the same Maven local repo the install
     * writes to — the caller-resolved {@code --m2-dir} root, not the machine's.
     */
    @Test
    void m2_install_is_checked_under_the_redirected_m2_dir(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Path cache = tmp.resolve("cache");
        Path mod = tmp.resolve("mod");
        Files.createDirectories(mod);
        JkBuild project = JkBuild.builder(Project.builder("cc.jumpkick", "jk-foo", "1.0")
                        .jdkMajor(25)
                        .java(25)
                        .m2install(true)
                        .build())
                .build();
        BuildLayout layout = BuildLayout.of(mod, project);
        Files.createDirectories(layout.mainJar().getParent());
        Files.writeString(layout.mainJar(), "jar-bytes");
        Coordinate coord = Coordinate.of("cc.jumpkick", "jk-foo", "1.0");
        Path pomFile = tmp.resolve("jk-foo.pom");
        Files.writeString(pomFile, PublishablePom.render(project, null).xml());

        String prevStore = System.getProperty("jk.env.JK_STORE_DIR");
        String prevInstall = System.getProperty("jk.m2.install");
        System.setProperty("jk.env.JK_STORE_DIR", store.toAbsolutePath().toString());
        System.setProperty("jk.m2.install", "true");
        try {
            InstallPlans.writeToLocalStore(cache, MavenLayout.artifactPath(coord), layout.mainJar());
            InstallPlans.writeToLocalStore(cache, MavenLayout.pomPath(coord), pomFile);

            // On the shelf but not yet in the redirected repo: the install still has work to do.
            Path m2Dir = tmp.resolve("m2-redirected");
            assertThat(InstallPlans.alreadyInstalled(project, layout, cache, m2Dir))
                    .isFalse();

            Path repo = m2Dir.resolve("repository");
            Path m2Jar = repo.resolve(MavenLayout.artifactPath(coord));
            Path m2Pom = repo.resolve(MavenLayout.pomPath(coord));
            Files.createDirectories(m2Jar.getParent());
            Files.createDirectories(m2Pom.getParent());
            Files.copy(layout.mainJar(), m2Jar);
            Files.copy(pomFile, m2Pom);
            assertThat(InstallPlans.alreadyInstalled(project, layout, cache, m2Dir))
                    .isTrue();
            assertThat(InstallPlans.alreadyInstalled(project, layout, cache, tmp.resolve("other-m2")))
                    .as("a different --m2-dir has not been installed to")
                    .isFalse();
        } finally {
            if (prevStore != null) System.setProperty("jk.env.JK_STORE_DIR", prevStore);
            else System.clearProperty("jk.env.JK_STORE_DIR");
            if (prevInstall != null) System.setProperty("jk.m2.install", prevInstall);
            else System.clearProperty("jk.m2.install");
        }
    }

    @Test
    void skip_false_when_pom_bytes_differ(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Path cache = tmp.resolve("cache");
        Path mod = tmp.resolve("mod");
        Files.createDirectories(mod);
        JkBuild project = JkBuild.builder(Project.builder("cc.jumpkick", "jk-foo", "1.0")
                        .jdkMajor(25)
                        .java(25)
                        .m2install(false)
                        .build())
                .build();
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
            assertThat(InstallPlans.alreadyInstalled(project, layout, cache)).isFalse();
        } finally {
            if (prev != null) System.setProperty("jk.env.JK_STORE_DIR", prev);
            else System.clearProperty("jk.env.JK_STORE_DIR");
        }
    }
}
