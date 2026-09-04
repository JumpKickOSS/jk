// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Every machine-scoped {@code [engine]} key and every {@code JK_ENGINE_*} process env the product
 * reads. Docs, the dashboard dump, and {@link JkEngineConfig} all take this list; a guard fails
 * when they drift.
 */
public final class EngineControls {

    private EngineControls() {}

    /**
     * One published control. {@link #toml()} is empty for process-only env. {@link #env()} may list
     * several names separated by {@code /}. {@link #whenRead()} says when a change takes effect —
     * at engine start, per command, per job, or per idle cycle — because the keys differ and a
     * doc that said "read once at engine start" was wrong for three of five.
     */
    public record Control(String toml, String env, String defaultValue, String whenRead, String meaning) {}

    static final String ENGINE_START = "engine start";

    /** {@code [engine]} keys in {@code ~/.jk/config.toml}. */
    public static final List<Control> TABLE = List.of(
            control(
                    "max-heap-mb",
                    "JK_ENGINE_MAX_HEAP_MB",
                    "256; 512 when CI is set",
                    ENGINE_START,
                    "Engine process heap ceiling (-Xmx). 0 = uncapped."),
            control(
                    "jobs",
                    "JK_JOBS",
                    "cores (0 = all cores)",
                    "each command",
                    "Concurrent module/worker budget. CLI -j wins. Alias JK_ENGINE_JOBS."),
            control(
                    "continue",
                    "JK_CONTINUE",
                    "false; true when CI is set",
                    "each job",
                    "Keep going after a failed module. Does not change the verdict."),
            control(
                    "vfs-max-mb",
                    "JK_ENGINE_VFS_MAX_MB",
                    "32",
                    ENGINE_START,
                    "Per-job input-tree retain in MiB. 0 = off. CI does not bump this."),
            control(
                    "auto-warmup",
                    "JK_AUTO_WARMUP",
                    "true",
                    "each idle cycle",
                    "Idle AOT train and host calibration. false skips the whole pass."));

    /** {@code JK_ENGINE_*} env that is not an {@code [engine]} key. */
    public static final List<Control> PROCESS = List.of(
            control(
                    "",
                    "JK_ENGINE_EXE",
                    "unset",
                    ENGINE_START,
                    "Override engine binary instead of the product-lib jar."),
            control(
                    "",
                    "JK_ENGINE_JDK",
                    "unset",
                    ENGINE_START,
                    "JDK the engine JVM runs on. Same pin as [toolchain].jdk."),
            control(
                    "",
                    "JK_ENGINE_TRANSPORT",
                    "unix; tcp on Windows",
                    ENGINE_START,
                    "Force tcp or unix for the client-engine wire."),
            control("", "JK_ENGINE_HEARTBEAT_MS", "30000", ENGINE_START, "Heartbeat while async jobs run. 0 disables."),
            control("", "JK_ENGINE_JOB_DEADLINE_MS", "0", ENGINE_START, "Job wall deadline in ms. 0 = off."),
            control(
                    "",
                    "JK_ENGINE_JOB_DEADLINE_GRACE_MS",
                    "30000",
                    ENGINE_START,
                    "Join grace after a deadline cancel, in ms."),
            control(
                    "",
                    "JK_CANCEL_GRACE_MS",
                    "500",
                    ENGINE_START,
                    "Shared SIGTERM-to-SIGKILL window for forked workers on cancel, in ms; clamped to 5000."));

    /** TomlScan keys: {@code engine.max-heap-mb}, … */
    public static String[] tomlScanKeys() {
        return TABLE.stream().map(c -> "engine." + c.toml()).toArray(String[]::new);
    }

    /** Env names the product may read, including the {@code JK_ENGINE_JOBS} alias of {@code JK_JOBS}. */
    public static Set<String> envNames() {
        Set<String> out = new LinkedHashSet<>();
        TABLE.forEach(c -> addEnv(out, c.env()));
        PROCESS.forEach(c -> addEnv(out, c.env()));
        out.add("JK_ENGINE_JOBS");
        return out;
    }

    public static String tableMarkdown() {
        return markdown(
                "engine-config",
                "| Key | Env | Default | Read | Meaning |",
                "|---|---|---|---|---|",
                TABLE,
                c -> "| `" + c.toml() + "` | `" + c.env() + "` | " + c.defaultValue() + " | " + c.whenRead() + " | "
                        + c.meaning() + " |");
    }

    public static String processMarkdown() {
        return markdown(
                "engine-process",
                "| Env | Default | Meaning |",
                "|---|---|---|",
                PROCESS,
                c -> "| `" + c.env() + "` | " + c.defaultValue() + " | " + c.meaning() + " |");
    }

    private static String markdown(
            String id, String header, String rule, List<Control> rows, Function<Control, String> line) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- ").append(id).append(":start -->\n");
        sb.append(header).append('\n');
        sb.append(rule).append('\n');
        rows.forEach(c -> sb.append(line.apply(c)).append('\n'));
        sb.append("<!-- ").append(id).append(":end -->");
        return sb.toString();
    }

    private static void addEnv(Set<String> out, String env) {
        if (env == null || env.isBlank()) return;
        for (String part : env.split("/")) {
            String name = part.trim();
            if (!name.isEmpty()) out.add(name);
        }
    }

    /** Parseable row factory so both builds can regex the catalog. */
    static Control control(String toml, String env, String defaultValue, String whenRead, String meaning) {
        return new Control(toml, env, defaultValue, whenRead, meaning);
    }
}
