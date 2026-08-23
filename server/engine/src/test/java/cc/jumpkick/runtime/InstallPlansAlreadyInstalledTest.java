// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
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
        JkBuild project = JkBuild.builder(JkBuild.Project.builder("cc.jumpkick", "jk-foo", "1.0")
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

    @Test
    void skip_false_when_pom_bytes_differ(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Path cache = tmp.resolve("cache");
        Path mod = tmp.resolve("mod");
        Files.createDirectories(mod);
        JkBuild project = JkBuild.builder(JkBuild.Project.builder("cc.jumpkick", "jk-foo", "1.0")
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
