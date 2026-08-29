// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HookEnvTest {

    private static final String SEP = File.pathSeparator;
    private static final String PROJ = "/home/u/src/proj";
    private static final String JDK25 = "/home/u/.local/share/jk/jdks/temurin-25";
    private static final String GRAAL = "/home/u/.local/share/jk/jdks/graalvm-25";
    private static final String LOCAL_BIN = "/home/u/.local/bin";
    private static final String USER_JAVA = "/home/u/sdk/java";
    private static final String PRIOR_JDK = "/home/u/.sdkman/candidates/java/current";
    private static final String PROJ_A_JDK = "/home/u/src/proj-a/.jk/jdk";
    private static final String NVM = "/home/u/.nvm/bin";
    private static final String NVM_NODE = "/home/u/.nvm/versions/node/v24/bin";

    /** Host-normalized {@code home/bin} — same form {@link ToolchainPath#binOf} emits. */
    private static String bin(String home) {
        return Path.of(home).resolve("bin").toString();
    }

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
                Optional.of(Path.of(PROJ)), Map.of("JAVA_HOME", JDK25, "PATH", bin(JDK25) + SEP + LOCAL_BIN));
        var out = new StringBuilder();
        // Before activation: only PATH is set in the env, JAVA_HOME is unset.
        JkDiff.EnvSnapshot snap = k -> "PATH".equals(k) ? LOCAL_BIN : null;
        HookEnvCommand.emit(sh, target, JkDiff.empty(), snap, out);

        var s = out.toString();
        assertThat(extractEnvAssignment(s, "JAVA_HOME")).isEqualTo(JDK25);
        assertThat(extractEnvAssignment(s, "PATH")).isEqualTo(bin(JDK25) + SEP + LOCAL_BIN);
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
            case "JAVA_HOME" -> JDK25;
            case "PATH" -> JDK25 + "/bin" + SEP + NVM + SEP + LOCAL_BIN;
            default -> null;
        };
        HookEnvCommand.emit(sh, JkEnv.Target.empty(), prior, snap, out);

        var s = out.toString();
        assertThat(s).contains("unset JAVA_HOME");
        // Surgical strip — nvm stays; frozen local-bin-only restore must not happen.
        assertThat(extractEnvAssignment(s, "PATH")).isEqualTo(NVM + SEP + LOCAL_BIN);
        assertThat(extractEnvAssignment(s, "PATH")).isNotEqualTo(LOCAL_BIN);
        assertThat(s).contains("unset __JK_DIFF");
    }

    @Test
    void user_owned_java_bin_survives_activation_and_returns_on_leave() {
        var sh = new BashShell();
        // User's own JAVA_HOME with its bin on PATH — jk never owned either.
        JkDiff.EnvSnapshot before = k -> switch (k) {
            case "JAVA_HOME" -> USER_JAVA;
            case "PATH" -> USER_JAVA + "/bin" + SEP + LOCAL_BIN;
            default -> null;
        };
        var target = new JkEnv.Target(Optional.of(Path.of(PROJ)), Map.of("JAVA_HOME", JDK25));
        var activate = new StringBuilder();
        HookEnvCommand.emit(sh, target, JkDiff.empty(), before, activate);
        // Activation prepends jk's bin ahead of the user's — it does not strip it.
        assertThat(extractEnvAssignment(activate.toString(), "PATH"))
                .isEqualTo(bin(JDK25) + SEP + USER_JAVA + "/bin" + SEP + LOCAL_BIN);

        // Leaving: jk's bin goes, the user's bin and JAVA_HOME are back untouched.
        var prior = JkDiff.parse(extractEnvAssignment(activate.toString(), "__JK_DIFF"));
        JkDiff.EnvSnapshot inside = k -> switch (k) {
            case "JAVA_HOME" -> JDK25;
            case "PATH" -> bin(JDK25) + SEP + USER_JAVA + "/bin" + SEP + LOCAL_BIN;
            default -> null;
        };
        var leave = new StringBuilder();
        HookEnvCommand.emit(sh, JkEnv.Target.empty(), prior, inside, leave);
        assertThat(extractEnvAssignment(leave.toString(), "JAVA_HOME")).isEqualTo(USER_JAVA);
        assertThat(extractEnvAssignment(leave.toString(), "PATH")).isEqualTo(USER_JAVA + "/bin" + SEP + LOCAL_BIN);
    }

    @Test
    void jdk_only_project_leaves_user_owned_graal_bin_alone() {
        var sh = new BashShell();
        // Project pins only a JDK; GRAALVM_HOME belongs to the user and is not managed.
        var prior = new JkDiff(Map.of("JAVA_HOME", JkDiff.UNSET_SENTINEL));
        var target = new JkEnv.Target(Optional.of(Path.of(PROJ)), Map.of("JAVA_HOME", JDK25));
        JkDiff.EnvSnapshot snap = k -> switch (k) {
            case "JAVA_HOME" -> JDK25;
            case "GRAALVM_HOME" -> GRAAL;
            case "PATH" -> JDK25 + "/bin" + SEP + GRAAL + "/bin" + SEP + LOCAL_BIN;
            default -> null;
        };
        var out = new StringBuilder();
        HookEnvCommand.emit(sh, target, prior, snap, out);
        // jk rewrites its own JDK bin to the host Path form; the user-owned Graal bin stays as-is.
        assertThat(extractEnvAssignment(out.toString(), "PATH"))
                .isEqualTo(bin(JDK25) + SEP + GRAAL + "/bin" + SEP + LOCAL_BIN);
    }

    @Test
    void switching_between_projects_keeps_pre_activation_values_in_diff() {
        var sh = new BashShell();
        // Already in project A: jk previously set JAVA_HOME (was the user's prior JDK).
        var prior = new JkDiff(Map.of("JAVA_HOME", PRIOR_JDK));
        // Now entering project B: new target.
        var target = new JkEnv.Target(
                Optional.of(Path.of("/home/u/src/proj-b")),
                Map.of("JAVA_HOME", JDK25, "PATH", bin(JDK25) + SEP + LOCAL_BIN));
        var out = new StringBuilder();
        // Live env has project A's values right now — should NOT overwrite our diff.
        JkDiff.EnvSnapshot snap = k -> switch (k) {
            case "JAVA_HOME" -> PROJ_A_JDK;
            case "PATH" -> PROJ_A_JDK + "/bin" + SEP + NVM + SEP + LOCAL_BIN;
            default -> null;
        };
        HookEnvCommand.emit(sh, target, prior, snap, out);

        var s = out.toString();
        assertThat(extractEnvAssignment(s, "JAVA_HOME")).isEqualTo(JDK25);
        // Live PATH neighbors survive the JDK bin swap.
        assertThat(extractEnvAssignment(s, "PATH")).isEqualTo(bin(JDK25) + SEP + NVM + SEP + LOCAL_BIN);
        var encoded = extractEnvAssignment(s, "__JK_DIFF");
        var next = JkDiff.parse(encoded);
        assertThat(next.previousValue("JAVA_HOME")).isEqualTo(PRIOR_JDK);
        assertThat(next.keys()).doesNotContain("PATH");
    }

    @Test
    void prompt_rewrite_does_not_clobber_path_entries_added_after_activation() {
        var sh = new BashShell();
        var prior = new JkDiff(Map.of("JAVA_HOME", JkDiff.UNSET_SENTINEL));
        var target = new JkEnv.Target(Optional.of(Path.of(PROJ)), Map.of("JAVA_HOME", JDK25));
        // User installed nvm after jk activate; live PATH already has the jk JDK bin + nvm.
        JkDiff.EnvSnapshot snap = k -> switch (k) {
            case "JAVA_HOME" -> JDK25;
            case "PATH" -> JDK25 + "/bin" + SEP + NVM_NODE + SEP + LOCAL_BIN;
            default -> null;
        };
        var out = new StringBuilder();
        HookEnvCommand.emit(sh, target, prior, snap, out);
        assertThat(extractEnvAssignment(out.toString(), "PATH"))
                .isEqualTo(bin(JDK25) + SEP + NVM_NODE + SEP + LOCAL_BIN);
    }

    @Test
    void zsh_emission_uses_export_syntax() {
        var sh = new ZshShell();
        var target = new JkEnv.Target(Optional.of(Path.of(PROJ)), Map.of("JAVA_HOME", JDK25));
        var out = new StringBuilder();
        HookEnvCommand.emit(sh, target, JkDiff.empty(), k -> null, out);
        assertThat(out.toString()).contains("export JAVA_HOME=" + JDK25);
    }

    @Test
    void fish_emission_uses_set_gx_syntax() {
        var sh = new FishShell();
        var target = new JkEnv.Target(Optional.of(Path.of(PROJ)), Map.of("JAVA_HOME", JDK25));
        var out = new StringBuilder();
        HookEnvCommand.emit(sh, target, JkDiff.empty(), k -> null, out);
        assertThat(out.toString()).contains("set -gx JAVA_HOME " + JDK25);
    }

    @Test
    void pwsh_emission_uses_env_assignment() {
        var sh = new PwshShell();
        var target = new JkEnv.Target(Optional.of(Path.of(PROJ)), Map.of("JAVA_HOME", JDK25));
        var out = new StringBuilder();
        HookEnvCommand.emit(sh, target, JkDiff.empty(), k -> null, out);
        assertThat(out.toString()).contains("$Env:JAVA_HOME = '" + JDK25 + "'");
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
