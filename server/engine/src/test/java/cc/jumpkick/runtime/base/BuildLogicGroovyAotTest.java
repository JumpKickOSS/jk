// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Processes;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.runtime.RepoGroupBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Groovy build-logic startup cache is recorded from a synthetic script, never a user's. */
@Tag("integration")
class BuildLogicGroovyAotTest {

    @Test
    void the_trainer_runs_its_synthetic_script_through_the_wrapper_and_records_a_cache(@TempDir Path dir)
            throws Exception {
        Path[] jars = BuildLogicGroovyHost.ensureJars(() -> RepoGroupBuilder.buildDefault(JkStores.storeCas()));
        String classpath = Classpaths.join(List.of(jars));
        Path home = JavaHomes.runningJavaHome();
        String java = JdkFingerprint.java(home).toString();
        Path aot = dir.resolve("groovy.aot");
        Path scratch = Files.createDirectories(dir.resolve("scratch"));
        Files.createDirectories(scratch.resolve("out"));

        Processes.Captured run = Processes.capture(
                BuildLogicGroovyHost.trainerCommand(java, classpath, aot, scratch), Duration.ofSeconds(120));
        String log = run.output();
        assertThat(run.exit()).as(log).isZero();
        assertThat(log).contains("trained 12");
        assertThat(scratch.resolve("out/demo/Version.java")).content().contains("1.2");
        assertThat(scratch.resolve("out/ant")).isDirectory();
        if (JvmOptions.hostFeature(home) >= 25) {
            assertThat(aot).as("JDK 25 records the cache at exit").isNotEmptyFile();
        }
    }
}
