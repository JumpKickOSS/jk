// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/**
 * Resolves {@link PluginTuning} layers: client ({@link #resolveClient}: CLI &gt; {@code JK_JVM_*}),
 * then project {@code [jvm]} ({@link #overlayProject}) at worker-fork time.
 *
 * <p>The {@code JK_JVM_*} variables are the shell's spelling of {@code --jvm-arg} and
 * {@code --ram-percent}: read once, client-side, where the process really is the caller's shell,
 * and carried on the request beside the flags. The engine is a daemon whose own environment is
 * whichever shell started it, so nothing on its side reads them — a resident engine that did kept
 * the first terminal's worker-JVM flags for every later one until {@code jk engine stop}.
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

    /**
     * Client layers only: CLI &gt; {@code JK_JVM_*} env (no TOML I/O). The environment is read
     * through {@link JkDirs#env}, so a test varies it per invocation the way it varies the layout.
     */
    public static PluginTuning resolveClient(PluginTuning cli) {
        return overlay(cli == null ? PluginTuning.NONE : cli, fromEnv(JkDirs::env));
    }

    /**
     * Overlay {@code base} (the client's flag/env layers) onto {@code projectDir/jk.toml [jvm]}:
     * base's scalars win; the table's args run first. Engine-side only (tomlj).
     */
    public static PluginTuning overlayProject(PluginTuning base, Path projectDir) {
        PluginTuning eff = base == null ? PluginTuning.NONE : base;
        return projectDir == null ? eff : overlay(eff, JkBuildParser.jvmTuning(ManifestPaths.manifestIn(projectDir)));
    }

    /** The {@code JK_*} environment layer as {@code env} answers it. Coercion via the shared {@link EnvValues}. */
    public static PluginTuning fromEnv(Function<String, @Nullable String> env) {
        return new PluginTuning(
                EnvValues.doubleValue(env, ENV_MAX_RAM).orElse(null),
                EnvValues.string(env, ENV_GC).orElse(null),
                EnvValues.bool(env, ENV_STRING_DEDUP).orElse(null),
                splitArgs(env.apply(ENV_ARGS)));
    }

    /** The keys {@code [jvm]} reads; any other is refused. */
    static final List<String> KEYS = List.of("max-ram-percent", "gc", "string-dedup", "args");

    /**
     * The collectors {@code [jvm] gc} names, in any case: {@code default} and {@code none} leave the
     * choice to the JVM.
     */
    static final List<String> GC_NAMES = List.of("default", "none", "g1", "zgc", "parallel", "serial");

    /**
     * The {@code [jvm]} table of a parsed {@code jk.toml}, or {@link PluginTuning#NONE} when it is
     * absent. Reached through {@link JkBuildParser#jvmTuning(Path)}, which owns the read, and run by
     * every full parse for its checks. An unknown key, a value of the wrong type, a {@code
     * max-ram-percent} outside {@code --ram-percent}'s range or a collector no fork knows fails the
     * parse at the key; {@code max-ram-percent} takes a TOML integer or float.
     */
    static PluginTuning fromToml(TomlTable root) {
        TomlTable jvm = TomlTableReader.optionalTable(root, "jvm", "`jvm`");
        if (jvm == null) return PluginTuning.NONE;
        TomlTableReader.rejectUnknownKeys(jvm, KEYS, "[jvm]");
        String gc = TomlTableReader.optionalString(jvm, "gc", "jvm.gc");
        if (gc != null && !GC_NAMES.contains(gc.toLowerCase(Locale.ROOT))) {
            throw JkBuildParseException.at(jvm, "gc", "jvm.gc must be one of " + String.join(", ", GC_NAMES));
        }
        return new PluginTuning(
                maxRamPercent(jvm),
                gc,
                TomlTableReader.optionalBool(jvm, "string-dedup", "jvm.string-dedup"),
                JkBuildParser.optionalStringList(jvm, "args", "jvm.args"));
    }

    /** {@code max-ram-percent}, a number above 0 and at most 100 as {@code --ram-percent} takes; null when absent. */
    private static @Nullable Double maxRamPercent(TomlTable jvm) {
        Object value = jvm.get(List.of("max-ram-percent"));
        if (value == null) return null;
        double pct = value instanceof Long n ? n : value instanceof Double d ? d : Double.NaN;
        if (pct > 0 && pct <= 100) return pct;
        throw JkBuildParseException.at(
                jvm, "max-ram-percent", "jvm.max-ram-percent must be a number above 0 and at most 100");
    }

    private static List<String> splitArgs(@Nullable String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : s.trim().split("\\s+")) if (!part.isBlank()) out.add(part);
        return out;
    }
}
