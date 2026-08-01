// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.WorkerClasspath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Thin pure-jk test-runner jars need WorkerClasspath expansion for PluginMain (JK-1347). */
class JUnitLauncherWorkerCpTest {

    @Test
    void thin_runner_classpath_includes_plugin_sdk(@TempDir Path dir) throws Exception {
        Path runner = dir.resolve("jk-test-runner.jar");
        Path sdk = dir.resolve("jk-plugin-sdk.jar");
        Files.writeString(runner, "runner");
        Files.writeString(sdk, "sdk");
        // Fake jar without PluginMain → findPluginSdk walks for target/...; plant monorepo-like layout
        Path monorepo = dir.resolve("ws");
        Path workerDir = monorepo.resolve("target/plugins/test-runner");
        Path sdkDir = monorepo.resolve("target/shared/plugin-sdk/lib");
        Files.createDirectories(workerDir);
        Files.createDirectories(sdkDir);
        Path thin = workerDir.resolve("jk-test-runner-1.jar");
        Path sdkJar = sdkDir.resolve("jk-plugin-sdk-1.jar");
        Files.writeString(thin, "thin");
        Files.writeString(sdkJar, "sdk");
        List<Path> paths = WorkerClasspath.paths(thin);
        assertThat(paths).contains(thin.toAbsolutePath().normalize(), sdkJar.toAbsolutePath().normalize());
    }
}
