// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Coordinate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk tool run <name>} honours an install: what {@link ToolLauncher#install} wrote is what
 * a later run reads back — recorded coordinate, {@code Main-Class} (never re-asked from the
 * user), classpath and JVM args. Anything unreadable or incomplete is {@code null}, so the caller
 * falls through to a fresh resolve instead of failing the run.
 */
class InstalledToolEnvsTest {

    private static Path installed(Path tmp, String name, String mainClass) throws IOException {
        Path envsRoot = tmp.resolve("envs");
        Path jar = tmp.resolve(name + ".jar");
        Files.writeString(jar, "jar-bytes");
        ToolEnv env = new ToolEnv(name, Coordinate.of("com.acme", name, "1.0.0"), mainClass, List.of(jar));
        ToolLauncher.install(envsRoot, tmp.resolve("bin"), tmp.resolve("jdk"), env, null, List.of("-Xmx64m"));
        return envsRoot;
    }

    @Test
    void a_run_by_installed_name_reuses_what_the_install_recorded(@TempDir Path tmp) throws Exception {
        Path envsRoot = installed(tmp, "checkstyle", "com.puppycrawl.tools.checkstyle.Main");

        InstalledToolEnvs.Installed installed = requireNonNull(InstalledToolEnvs.read(envsRoot, "checkstyle"));

        assertThat(installed.env().primary().toGav()).isEqualTo("com.acme:checkstyle:1.0.0");
        assertThat(installed.env().mainClass())
                .as("--main recorded at install time is reused, never repeated")
                .isEqualTo("com.puppycrawl.tools.checkstyle.Main");
        assertThat(installed.env().classpath()).hasSize(1);
        assertThat(installed.jvmArgs()).containsExactly("-Xmx64m");
        assertThat(installed.javaHome()).isEqualTo(tmp.resolve("jdk").toAbsolutePath());
    }

    @Test
    void an_uninstalled_name_is_null_so_the_catalog_gets_its_turn(@TempDir Path tmp) {
        assertThat(InstalledToolEnvs.read(tmp.resolve("envs"), "ktlint")).isNull();
    }

    @Test
    void a_gone_classpath_entry_falls_through_to_a_fresh_resolve(@TempDir Path tmp) throws Exception {
        Path envsRoot = installed(tmp, "checkstyle", "com.puppycrawl.tools.checkstyle.Main");
        Files.delete(tmp.resolve("checkstyle.jar"));

        assertThat(InstalledToolEnvs.read(envsRoot, "checkstyle"))
                .as("a stale install must not exec a missing classpath — re-resolve instead")
                .isNull();
    }

    @Test
    void a_kotlin_script_tool_is_the_launchers_business_not_this_paths(@TempDir Path tmp) throws Exception {
        Path envsRoot = installed(tmp, "greet", "kotlin-script");
        assertThat(InstalledToolEnvs.read(envsRoot, "greet")).isNull();
    }

    @Test
    void a_corrupt_env_json_is_null_not_an_error(@TempDir Path tmp) throws Exception {
        Path envDir = Files.createDirectories(tmp.resolve("envs/broken"));
        Files.writeString(envDir.resolve("env.json"), "{not json");
        assertThat(InstalledToolEnvs.read(tmp.resolve("envs"), "broken")).isNull();
    }
}
