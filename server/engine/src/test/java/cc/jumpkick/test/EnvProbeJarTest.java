// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The probe agent is cut from the runner jar: its classes alone, named as an agent on the boot class path. */
class EnvProbeJarTest {

    @Test
    void the_agent_jar_holds_the_probe_classes_and_names_itself_an_agent_on_the_boot_path(@TempDir Path tmp)
            throws Exception {
        Path runner = tmp.resolve("runner.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(runner))) {
            for (String name : new String[] {
                "cc/jumpkick/testrunner/env/EnvProbe.class",
                "cc/jumpkick/testrunner/env/EnvLookups.class",
                "cc/jumpkick/testrunner/TestRunner.class"
            }) {
                out.putNextEntry(new JarEntry(name));
                out.write(new byte[] {(byte) 0xca, (byte) 0xfe});
                out.closeEntry();
            }
        }

        String flag = EnvProbeJar.flag(runner, tmp.resolve("cache"), tmp.resolve("records"));
        Path agent = Path.of(flag.substring("-javaagent:".length(), flag.indexOf('=')));

        assertThat(flag).endsWith("=" + tmp.resolve("records").toAbsolutePath());
        try (JarFile jar = new JarFile(agent.toFile())) {
            assertThat(Collections.list(jar.entries()).stream().map(JarEntry::getName))
                    .contains(
                            "cc/jumpkick/testrunner/env/EnvProbe.class", "cc/jumpkick/testrunner/env/EnvLookups.class")
                    .doesNotContain("cc/jumpkick/testrunner/TestRunner.class");
            var main = jar.getManifest().getMainAttributes();
            assertThat(main.getValue("Premain-Class")).isEqualTo("cc.jumpkick.testrunner.env.EnvProbe");
            assertThat(main.getValue("Boot-Class-Path"))
                    .isEqualTo(agent.getFileName().toString());
        }
        assertThat(EnvProbeJar.flag(runner, tmp.resolve("cache"), tmp.resolve("records")))
                .as("cut once per runner content")
                .isEqualTo(flag);
    }
}
