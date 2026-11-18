// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.engine.plugin.PluginJar;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Before a run relies on the probe to hand the caller's environment to the programs a test starts,
 * the JDK is asked whether the hook takes, by the probe itself in a JVM of that JDK; one that cannot
 * be asked reads as a refusal, so its test JVMs get the whole environment rather than too little.
 */
@Tag("integration")
class EnvProbeHookTest {

    @Test
    void the_jdk_running_this_test_lets_the_probe_hook_its_process_launch(@TempDir Path tmp) throws Exception {
        String flag = EnvProbeJar.flag(
                PluginJar.TEST_RUNNER.locate(JkStores.storeCas()), tmp.resolve("cache"), tmp.resolve("records"));
        Path javaHome = Path.of(System.getProperty("java.home"));

        assertThat(EnvProbeHook.unhooked(javaHome, flag))
                .as("this JDK starts programs as the probe expects")
                .isNull();
        Path agent = Objects.requireNonNull(EnvProbeHook.jarOf(flag));
        try (var kept = Files.list(Objects.requireNonNull(agent.getParent()))) {
            assertThat(kept.map(p -> p.getFileName().toString()))
                    .as("the verdict is kept beside the probe, and no check file is left")
                    .anyMatch(n -> n.startsWith("hook-") && n.endsWith(".txt"))
                    .noneMatch(n -> n.endsWith(".tmp"));
        }
    }

    @Test
    void a_jdk_the_check_cannot_run_on_reads_as_a_refusal(@TempDir Path tmp) throws Exception {
        String flag = EnvProbeJar.flag(
                PluginJar.TEST_RUNNER.locate(JkStores.storeCas()), tmp.resolve("cache"), tmp.resolve("records"));
        Path noJdk = Files.createDirectories(tmp.resolve("not-a-jdk"));

        assertThat(EnvProbeHook.unhooked(noJdk, flag))
                .as("no java to ask: the run takes the whole environment instead")
                .contains("did not run");
    }
}
