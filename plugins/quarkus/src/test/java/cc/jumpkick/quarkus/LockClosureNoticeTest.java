// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code jk lock} hears from the lock-closure fork: the fork resolves the closure with
 * Quarkus's own resolver and checks the model against the lock being written, as the augment does,
 * so a runtime artifact the resolver reaches and the lock does not name — one a build would leave
 * out of the application — is a notice naming it while the lock is written, not on the first build.
 * The resolver runs offline over a closure repository the test lays out, and the notice is relayed
 * from the fork's file to the step the way the worker relays it.
 */
class LockClosureNoticeTest {

    private static final String QUARKUS = "9.9.9";

    private @Nullable String savedSettings;

    @BeforeEach
    void isolateSettings(@TempDir Path tmp) throws Exception {
        // The developer's own settings.xml (a mirror, a proxy) must not decide this test.
        Path settings = tmp.resolve("settings.xml");
        Files.writeString(settings, "<settings/>");
        savedSettings = System.getProperty("maven.settings");
        System.setProperty("maven.settings", settings.toString());
    }

    @AfterEach
    void restoreSettings() {
        if (savedSettings == null) System.clearProperty("maven.settings");
        else System.setProperty("maven.settings", savedSettings);
    }

    @Test
    void a_lock_whose_closure_leaves_a_runtime_artifact_outside_it_says_so(@TempDir Path dir) throws Exception {
        Path repo = closureRepository(dir);
        LockedClosure lock = LockedClosure.of(List.of(new LockedClosure.Artifact(
                "com.example", "widget", "1.0", repo.resolve("com/example/widget/1.0/widget-1.0.jar"), false)));
        Path notices = dir.resolve("notices.txt");

        Map<String, Path> files = resolve(dir, repo, lock, notices);

        assertThat(files).containsKey("com.example:widget-extra:1.0");
        FakeBuildIo step = new FakeBuildIo(dir.resolve("step"), "quarkus");
        ForkNotices.relay(notices, step);
        assertThat(step.diagnostics()).singleElement().satisfies(notice -> assertThat(notice)
                .startsWith("notice: NOT shipped — outside jk-lock.toml")
                .contains("com.example:widget-extra:1.0")
                .doesNotContain("com.example:widget:"));
    }

    @Test
    void a_lock_that_names_the_whole_closure_says_nothing(@TempDir Path dir) throws Exception {
        Path repo = closureRepository(dir);
        LockedClosure lock = LockedClosure.of(List.of(
                new LockedClosure.Artifact(
                        "com.example", "widget", "1.0", repo.resolve("com/example/widget/1.0/widget-1.0.jar"), false),
                new LockedClosure.Artifact(
                        "com.example",
                        "widget-extra",
                        "1.0",
                        repo.resolve("com/example/widget-extra/1.0/widget-extra-1.0.jar"),
                        false)));
        Path notices = dir.resolve("notices.txt");

        resolve(dir, repo, lock, notices);

        assertThat(notices).doesNotExist();
    }

    private static Map<String, Path> resolve(Path dir, Path repo, LockedClosure lock, Path notices) throws Exception {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Files.writeString(classes.resolve("app.properties"), "greeting=hello\n");
        return QuarkusLockClosureMain.resolve(
                lock,
                repo,
                classes,
                dir.resolve("scratch"),
                "com.example",
                "app",
                "1.0.0",
                QUARKUS,
                RepositoryRoutes.of(List.of()),
                true,
                ForkNotices.to(notices));
    }

    /**
     * The closure repository the engine would link from the store: the platform BOM, and {@code
     * widget}, whose POM pulls in {@code widget-extra} at runtime.
     */
    private static Path closureRepository(Path dir) throws Exception {
        Path repo = dir.resolve("closure");
        pom(repo, "io.quarkus.platform", "quarkus-bom", QUARKUS, "pom", "");
        pom(repo, "com.example", "widget", "1.0", "jar", """
                <dependencies>
                  <dependency>
                    <groupId>com.example</groupId>
                    <artifactId>widget-extra</artifactId>
                    <version>1.0</version>
                  </dependency>
                </dependencies>
                """);
        jar(repo, "com.example", "widget", "1.0");
        pom(repo, "com.example", "widget-extra", "1.0", "jar", "");
        jar(repo, "com.example", "widget-extra", "1.0");
        return repo;
    }

    private static void pom(Path repo, String group, String artifact, String version, String packaging, String body)
            throws Exception {
        Path pom = dir(repo, group, artifact, version).resolve(artifact + "-" + version + ".pom");
        Files.writeString(pom, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <packaging>%s</packaging>
                  %s
                </project>
                """.formatted(group, artifact, version, packaging, body));
    }

    private static void jar(Path repo, String group, String artifact, String version) throws Exception {
        Path jar = dir(repo, group, artifact, version).resolve(artifact + "-" + version + ".jar");
        AppJar.write(Files.createDirectories(repo.resolveSibling(artifact + "-classes")), jar);
    }

    private static Path dir(Path repo, String group, String artifact, String version) throws Exception {
        return Files.createDirectories(
                repo.resolve(group.replace('.', '/')).resolve(artifact).resolve(version));
    }
}
