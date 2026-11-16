// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.git;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.GitConfigFiles;
import cc.jumpkick.testing.SysProps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * JGit takes the user's git configuration from {@code GIT_CONFIG_GLOBAL} when it is set, as the
 * {@code git} command does, and from where JGit always looked when it is not.
 */
@ExtendWith(SysProps.class)
class JGitEnvironmentTest {

    /** The harness points this JVM at its sandbox through the properties; these cases set the variables. */
    @BeforeEach
    void noHarnessProperties() {
        System.clearProperty(GitConfigFiles.GLOBAL_PROPERTY);
        System.clearProperty(GitConfigFiles.NOSYSTEM_PROPERTY);
    }

    @Test
    void git_config_global_names_the_user_configuration(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("gitconfig");
        Files.writeString(file, "[user]\n\tname = Sandbox\n");

        FileBasedConfig config =
                reader(Map.of("GIT_CONFIG_GLOBAL", file.toString())).openUserConfig(null, FS.DETECTED);
        config.load();

        assertThat(config.getFile().toPath()).isEqualTo(file);
        assertThat(config.getString("user", null, "name")).isEqualTo("Sandbox");
    }

    @Test
    void without_it_jgit_reads_what_it_always_read() {
        SystemReader plain = env(Map.of());

        FileBasedConfig config = new JGitEnvironment.Reader(plain).openUserConfig(null, FS.DETECTED);

        assertThat(config.getFile())
                .isEqualTo(plain.openUserConfig(null, FS.DETECTED).getFile());
    }

    @Test
    void under_it_the_configuration_home_is_its_directory_and_the_system_file_is_skipped(@TempDir Path tmp) {
        Path file = tmp.resolve("gitconfig");
        SystemReader reader = reader(Map.of("GIT_CONFIG_GLOBAL", file.toString(), "GIT_CONFIG_NOSYSTEM", "1"));

        assertThat(reader.getXdgConfigDirectory(FS.DETECTED)).isEqualTo(tmp);
        assertThat(reader.openSystemConfig(null, FS.DETECTED).getFile()).isNull();
    }

    private static SystemReader reader(Map<String, String> vars) {
        return new JGitEnvironment.Reader(env(vars));
    }

    /** JGit's own reader with the environment {@code vars} in place of the process's. */
    private static SystemReader env(Map<String, String> vars) {
        return new SystemReader.Delegate(SystemReader.getInstance()) {
            @Override
            public @Nullable String getenv(String name) {
                return vars.get(name);
            }
        };
    }
}
