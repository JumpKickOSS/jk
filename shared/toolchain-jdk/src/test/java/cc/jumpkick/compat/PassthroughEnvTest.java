// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PassthroughEnvTest {

    @Test
    void strips_overrides_and_inherits_rest() {
        Map<String, String> env = new HashMap<>();
        env.put("JAVA_TOOL_OPTIONS", "-Xmx512m");
        env.put("_JAVA_OPTIONS", "-Dfoo=bar");
        env.put("MAVEN_OPTS", "-Xmx2g");
        env.put("GRADLE_OPTS", "-Dorg.gradle.daemon=false");
        env.put("KOTLIN_HOME", "/opt/kotlin");
        // JDK_HOME is in the strip set with the other toolchain overrides, so an ambient
        // JDK_HOME cannot out-vote the JDK jk just picked.
        env.put("JDK_HOME", "/opt/some-other-jdk");
        env.put("PATH", "/usr/bin:/bin");
        env.put("HOME", "/home/alice");

        PassthroughEnv.apply(env, null);

        assertThat(env)
                .doesNotContainKeys(
                        "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_HOME", "MAVEN_OPTS", "GRADLE_OPTS", "KOTLIN_HOME");
        assertThat(env).containsEntry("PATH", "/usr/bin:/bin");
        assertThat(env).containsEntry("HOME", "/home/alice");
    }

    @Test
    void sets_java_home_and_prepends_bin_to_path(@TempDir Path tempDir) {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", "/usr/bin");

        PassthroughEnv.apply(env, tempDir);

        assertThat(env.get("JAVA_HOME")).isEqualTo(tempDir.toAbsolutePath().toString());
        assertThat(env.get("PATH"))
                .startsWith(tempDir.resolve("bin").toAbsolutePath().toString())
                .endsWith("/usr/bin");
    }

    @Test
    void windows_shaped_env_prepends_to_the_existing_path_key(@TempDir Path tempDir) {
        // A Windows env map spells PATH "Path", and a plain map does not fold case on lookup.
        // Writing the canonical "PATH" would add a second key, leaving the ambient java first on
        // the one the child process actually reads.
        Map<String, String> env = new HashMap<>();
        env.put("Path", "C:\\Windows\\System32");
        env.put("Java_Tool_Options", "-Xmx512m");

        PassthroughEnv.apply(env, tempDir);

        var pathKeys =
                env.keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).toList();
        assertThat(pathKeys).containsExactly("Path");
        String bin = tempDir.resolve("bin").toAbsolutePath().toString();
        assertThat(env.get("Path")).isEqualTo(bin + File.pathSeparator + "C:\\Windows\\System32");
        // Same key-folding applies to the strip list, not just to PATH.
        assertThat(env).doesNotContainKey("Java_Tool_Options");
    }

    @Test
    void null_java_home_leaves_path_alone() {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", "/usr/bin");

        PassthroughEnv.apply(env, null);

        assertThat(env).doesNotContainKey("JAVA_HOME");
        assertThat(env).containsEntry("PATH", "/usr/bin");
    }
}
