// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.tomlj.TomlTable;

/**
 * Resolves {@link PluginTuning} layers: client ({@link #resolveClient}: CLI &gt; {@code JK_JVM_*}),
 * then project {@code [jvm]} ({@link #overlayProject}) at worker-fork time.
 */
public final class PluginTunings {

    private PluginTunings() {}

    public static final String ENV_MAX_RAM = "JK_MAX_RAM_PERCENT";
    public static final String ENV_GC = "JK_JVM_GC";
    public static final String ENV_STRING_DEDUP = "JK_JVM_STRING_DEDUP";
    public static final String ENV_ARGS = "JK_JVM_ARGS";

    /**
     * Overlay {@code high} onto {@code low}: high's non-null scalars win; args concatenate (low
     * first).
     */
    private static PluginTuning overlay(PluginTuning high, PluginTuning low) {
        List<String> args = new ArrayList<>(low.extraArgs());
        args.addAll(high.extraArgs());
        return new PluginTuning(
                high.maxRamPercent() != null ? high.maxRamPercent() : low.maxRamPercent(),
                high.gc() != null ? high.gc() : low.gc(),
                high.stringDedup() != null ? high.stringDedup() : low.stringDedup(),
                args);
    }

    /** Full resolution: CLI &gt; env &gt; project {@code [jvm]}. */
    public static PluginTuning resolve(PluginTuning cli, Path projectDir) {
        return overlayProject(resolveClient(cli), projectDir);
    }

    /** Client layers only: CLI &gt; {@code JK_JVM_*} env (no TOML I/O). */
    public static PluginTuning resolveClient(PluginTuning cli) {
        return overlay(cli == null ? PluginTuning.NONE : cli, fromEnv());
    }

    /**
     * Overlay {@code base} (the client's flag/env layers) onto {@code projectDir/jk.toml [jvm]}:
     * base's scalars win; the table's args run first. Engine-side only (tomlj).
     */
    public static PluginTuning overlayProject(PluginTuning base, Path projectDir) {
        PluginTuning eff = base == null ? PluginTuning.NONE : base;
        return projectDir == null
                ? eff
                : overlay(eff, JkBuildParser.jvmTuning(projectDir.resolve(ManifestPaths.MANIFEST)));
    }

    /** The {@code JK_*} environment layer. Coercion via the shared {@link EnvValues}. */
    public static PluginTuning fromEnv() {
        return new PluginTuning(
                EnvValues.doubleValue(System::getenv, ENV_MAX_RAM).orElse(null),
                EnvValues.string(System::getenv, ENV_GC).orElse(null),
                EnvValues.bool(System::getenv, ENV_STRING_DEDUP).orElse(null),
                splitArgs(System.getenv(ENV_ARGS)));
    }

    /**
     * The {@code [jvm]} table of a parsed {@code jk.toml}, or {@link PluginTuning#NONE} when it is
     * absent. Reached through {@link JkBuildParser#jvmTuning(Path)}, which owns the read. Coercion
     * via the shared {@link TomlValues} ({@code max-ram-percent} accepts a TOML integer or float;
     * {@code args} keeps only string elements).
     */
    static PluginTuning fromToml(TomlTable root) {
        TomlTable jvm = root.getTable("jvm");
        if (jvm == null) return PluginTuning.NONE;
        return new PluginTuning(
                TomlValues.optDouble(jvm, "max-ram-percent").orElse(null),
                TomlValues.optString(jvm, "gc").orElse(null),
                TomlValues.optBoolean(jvm, "string-dedup").orElse(null),
                TomlValues.stringList(jvm, "args"));
    }

    private static List<String> splitArgs(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : s.trim().split("\\s+")) if (!part.isBlank()) out.add(part);
        return out;
    }
}
