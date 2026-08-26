// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HookEnvTest {

    @Test
    void no_project_with_no_prior_diff_emits_nothing() {
        var sh = new BashShell();
        var out = new StringBuilder();
        HookEnvCommand.emit(sh, JkEnv.Target.empty(), JkDiff.empty(), k -> null, out);
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void entering_a_project_exports_target_vars_and_stores_diff() {
        var sh = new BashShell();
        var target = new JkEnv.Target(
                Optional.of(Path.of("/proj")),
                Map.of(
                        "JAVA_HOME", "/opt/jdk-25",
                        "PATH", "/opt/jdk-25/bin:/usr/bin"));
        var out = new StringBuilder();
        // Before activation: only PATH is set in the env, JAVA_HOME is unset.
        JkDiff.EnvSnapshot snap = k -> "PATH".equals(k) ? "/usr/bin" : null;
        HookEnvCommand.emit(sh, target, JkDiff.empty(), snap, out);

        var s = out.toString();
        assertThat(s).contains("export JAVA_HOME=/opt/jdk-25");
        assertThat(s).contains("export PATH=/opt/jdk-25/bin:/usr/bin");
        assertThat(s).contains("export __JK_DIFF=");
        // PATH is not frozen into the diff — only homes are.
        var next = JkDiff.parse(extractEnvAssignment(s, "__JK_DIFF"));
        assertThat(next.keys()).containsExactly("JAVA_HOME");
        assertThat(next.wasUnset("JAVA_HOME")).isTrue();
    }

    @Test
    void leaving_strips_toolchain_bins_and_preserves_neighbors() {
        var sh = new BashShell();
        // Prior diff: jk previously set JAVA_HOME (was unset before). PATH is not tracked.
        var prior = new JkDiff(orderedMap("JAVA_HOME", JkDiff.UNSET_SENTINEL));
        var out = new StringBuilder();
        JkDiff.EnvSnapshot snap = k -> switch (k) {
            case "JAVA_HOME" -> "/opt/jdk-25";
            case "PATH" -> "/opt/jdk-25/bin:/home/u/.nvm/bin:/usr/bin";
            default -> null;
        };
        HookEnvCommand.emit(sh, JkEnv.Target.empty(), prior, snap, out);

        var s = out.toString();
        assertThat(s).contains("unset JAVA_HOME");
        // Surgical strip — nvm stays; frozen /usr/bin-only restore must not happen.
        assertThat(s).contains("export PATH=/home/u/.nvm/bin:/usr/bin");
        assertThat(s).doesNotContain("export PATH=/usr/bin\n");
        assertThat(s).contains("unset __JK_DIFF");
    }

    @Test
    void user_owned_java_bin_survives_activation_and_returns_on_leave() {
        var sh = new BashShell();
        // User's own JAVA_HOME with its bin on PATH — jk never owned either.
        JkDiff.EnvSnapshot before = k -> switch (k) {
            case "JAVA_HOME" -> "/home/u/sdk/java";
            case "PATH" -> "/home/u/sdk/java/bin:/usr/bin";
            default -> null;
        };
        var target = new JkEnv.Target(Optional.of(Path.of("/proj")), Map.of("JAVA_HOME", "/opt/jdk-25"));
        var activate = new StringBuilder();
        HookEnvCommand.emit(sh, target, JkDiff.empty(), before, activate);
        // Activation prepends jk's bin ahead of the user's — it does not strip it.
        assertThat(activate.toString()).contains("export PATH=/opt/jdk-25/bin:/home/u/sdk/java/bin:/usr/bin");

        // Leaving: jk's bin goes, the user's bin and JAVA_HOME are back untouched.
        var prior = JkDiff.parse(extractEnvAssignment(activate.toString(), "__JK_DIFF"));
        JkDiff.EnvSnapshot inside = k -> switch (k) {
            case "JAVA_HOME" -> "/opt/jdk-25";
            case "PATH" -> "/opt/jdk-25/bin:/home/u/sdk/java/bin:/usr/bin";
            default -> null;
        };
        var leave = new StringBuilder();
        HookEnvCommand.emit(sh, JkEnv.Target.empty(), prior, inside, leave);
        assertThat(leave.toString()).contains("export JAVA_HOME=/home/u/sdk/java");
        assertThat(leave.toString()).contains("export PATH=/home/u/sdk/java/bin:/usr/bin");
    }

    @Test
    void jdk_only_project_leaves_user_owned_graal_bin_alone() {
        var sh = new BashShell();
        // Project pins only a JDK; GRAALVM_HOME belongs to the user and is not managed.
        var prior = new JkDiff(Map.of("JAVA_HOME", JkDiff.UNSET_SENTINEL));
        var target = new JkEnv.Target(Optional.of(Path.of("/proj")), Map.of("JAVA_HOME", "/opt/jdk-25"));
        JkDiff.EnvSnapshot snap = k -> switch (k) {
            case "JAVA_HOME" -> "/opt/jdk-25";
            case "GRAALVM_HOME" -> "/opt/graal";
            case "PATH" -> "/opt/jdk-25/bin:/opt/graal/bin:/usr/bin";
            default -> null;
        };
        var out = new StringBuilder();
        HookEnvCommand.emit(sh, target, prior, snap, out);
        assertThat(out.toString()).contains("export PATH=/opt/jdk-25/bin:/opt/graal/bin:/usr/bin");
    }

    @Test
    void switching_between_projects_keeps_pre_activation_values_in_diff() {
        var sh = new BashShell();
        // Already in project A: jk previously set JAVA_HOME (was /sys/jdk).
        var prior = new JkDiff(Map.of("JAVA_HOME", "/sys/jdk"));
        // Now entering project B: new target.
        var target = new JkEnv.Target(
                Optional.of(Path.of("/b")),
                Map.of(
                        "JAVA_HOME", "/opt/jdk-25",
                        "PATH", "/opt/jdk-25/bin:/usr/bin"));
        var out = new StringBuilder();
        // Live env has project A's values right now — should NOT overwrite our diff.
        JkDiff.EnvSnapshot snap = k -> switch (k) {
            case "JAVA_HOME" -> "/proj-a/jdk";
            case "PATH" -> "/proj-a/jdk/bin:/home/u/.nvm/bin:/usr/bin";
            default -> null;
        };
        HookEnvCommand.emit(sh, target, prior, snap, out);

        var s = out.toString();
        assertThat(s).contains("export JAVA_HOME=/opt/jdk-25");
        // Live PATH neighbors survive the JDK bin swap.
        assertThat(s).contains("export PATH=/opt/jdk-25/bin:/home/u/.nvm/bin:/usr/bin");
        var encoded = extractEnvAssignment(s, "__JK_DIFF");
        var next = JkDiff.parse(encoded);
        assertThat(next.previousValue("JAVA_HOME")).isEqualTo("/sys/jdk");
        assertThat(next.keys()).doesNotContain("PATH");
    }

    @Test
    void prompt_rewrite_does_not_clobber_path_entries_added_after_activation() {
        var sh = new BashShell();
        var prior = new JkDiff(Map.of("JAVA_HOME", JkDiff.UNSET_SENTINEL));
        var target = new JkEnv.Target(Optional.of(Path.of("/proj")), Map.of("JAVA_HOME", "/opt/jdk-25"));
        // User installed nvm after jk activate; live PATH already has the jk JDK bin + nvm.
        JkDiff.EnvSnapshot snap = k -> switch (k) {
            case "JAVA_HOME" -> "/opt/jdk-25";
            case "PATH" -> "/opt/jdk-25/bin:/home/u/.nvm/versions/node/v24/bin:/usr/bin";
            default -> null;
        };
        var out = new StringBuilder();
        HookEnvCommand.emit(sh, target, prior, snap, out);
        assertThat(out.toString()).contains("export PATH=/opt/jdk-25/bin:/home/u/.nvm/versions/node/v24/bin:/usr/bin");
    }

    @Test
    void zsh_emission_uses_export_syntax() {
        var sh = new ZshShell();
        var target = new JkEnv.Target(Optional.of(Path.of("/p")), Map.of("JAVA_HOME", "/opt/jdk"));
        var out = new StringBuilder();
        HookEnvCommand.emit(sh, target, JkDiff.empty(), k -> null, out);
        assertThat(out.toString()).contains("export JAVA_HOME=/opt/jdk");
    }

    @Test
    void fish_emission_uses_set_gx_syntax() {
        var sh = new FishShell();
        var target = new JkEnv.Target(Optional.of(Path.of("/p")), Map.of("JAVA_HOME", "/opt/jdk"));
        var out = new StringBuilder();
        HookEnvCommand.emit(sh, target, JkDiff.empty(), k -> null, out);
        assertThat(out.toString()).contains("set -gx JAVA_HOME /opt/jdk");
    }

    @Test
    void pwsh_emission_uses_env_assignment() {
        var sh = new PwshShell();
        var target = new JkEnv.Target(Optional.of(Path.of("/p")), Map.of("JAVA_HOME", "/opt/jdk"));
        var out = new StringBuilder();
        HookEnvCommand.emit(sh, target, JkDiff.empty(), k -> null, out);
        assertThat(out.toString()).contains("$Env:JAVA_HOME = '/opt/jdk'");
    }

    /** Extract the value of {@code export KEY=value} (or fish/pwsh equivalent). */
    private static String extractEnvAssignment(String script, String key) {
        for (var line : script.split("\n")) {
            var bash = "export " + key + "=";
            if (line.startsWith(bash)) {
                var v = line.substring(bash.length());
                if (v.startsWith("'") && v.endsWith("'")) v = v.substring(1, v.length() - 1);
                return v.replace("'\\''", "'");
            }
        }
        return "";
    }

    private static <K, V> Map<K, V> orderedMap(Object... kvs) {
        var m = new LinkedHashMap<K, V>();
        for (int i = 0; i < kvs.length; i += 2) {
            @SuppressWarnings("unchecked")
            K k = (K) kvs[i];
            @SuppressWarnings("unchecked")
            V v = (V) kvs[i + 1];
            m.put(k, v);
        }
        return m;
    }
}
