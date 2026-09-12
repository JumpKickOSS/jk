// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.PluginCommandExec;
import cc.jumpkick.plugin.build.ProjectFacts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deploying an AAB goes through a universal APK that bundletool writes into a scratch directory.
 * The directory exists for the install and for nothing after it — including a deploy that fails
 * halfway, which is the one a developer repeats until it works.
 */
class DeployCommandScratchTest {

    @Test
    void a_deploy_that_fails_building_the_universal_apk_leaves_no_scratch_directory_behind(@TempDir Path tmp)
            throws Exception {
        Path aab = Files.writeString(tmp.resolve("app-1.0.0.aab"), "not read");
        Path aapt2 = tmp.resolve("aapt2.jar");
        // A classifier for another OS: the extraction refuses it after the scratch dir exists.
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(aapt2))) {
            zip.putNextEntry(new ZipEntry("README"));
            zip.closeEntry();
        }
        Files.writeString(tmp.resolve("AndroidManifest.xml"), """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest package="com.example.app">
                  <application>
                    <activity android:name=".MainActivity">
                      <intent-filter><action android:name="android.intent.action.MAIN"/></intent-filter>
                    </activity>
                  </application>
                </manifest>
                """);
        PluginCommandExec exec = exec(
                tmp,
                aab,
                Map.of(
                        "adb", tmp.resolve("adb"),
                        "bundletool", Files.createDirectories(tmp.resolve("bundletool")),
                        "aapt2", aapt2));
        Set<Path> before = scratchDirs();

        assertThatThrownBy(() -> DeployCommand.run(exec))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("aapt2");

        assertThat(scratchDirs()).isEqualTo(before);
    }

    /** Every {@code jk-deploy-*} directory in the JVM's temp dir right now. */
    private static Set<Path> scratchDirs() throws IOException {
        Path tmpdir = Path.of(Objects.requireNonNull(System.getProperty("java.io.tmpdir")));
        try (var children = Files.list(tmpdir)) {
            return children.filter(p -> p.getFileName().toString().startsWith("jk-deploy-"))
                    .collect(Collectors.toSet());
        }
    }

    private static PluginCommandExec exec(Path moduleDir, Path artifact, Map<String, Path> extras) {
        List<String> lines = new ArrayList<>();
        return new PluginCommandExec() {
            @Override
            public List<String> args() {
                return List.of();
            }

            @Override
            public PluginConfig config() {
                return new PluginConfig(
                        "android",
                        Map.of(
                                "namespace",
                                "com.example.app",
                                "debug-store-dir",
                                moduleDir.resolve("store").toString()));
            }

            @Override
            public ProjectFacts project() {
                return new ProjectFacts("com.example", "app", "1.0.0", 25, null, false, false, Map.of());
            }

            @Override
            public Path moduleDir() {
                return moduleDir;
            }

            @Override
            public Optional<Path> extra(String name) {
                return Optional.ofNullable(extras.get(name));
            }

            @Override
            public Optional<Path> mainArtifact() {
                return Optional.of(artifact);
            }

            @Override
            public void out(String line) {
                lines.add(line);
            }

            @Override
            public Path javaHome() {
                return Path.of(Objects.requireNonNull(System.getProperty("java.home")));
            }

            @Override
            public boolean offline() {
                return true;
            }

            @Override
            public void label(String text) {}
        };
    }
}
