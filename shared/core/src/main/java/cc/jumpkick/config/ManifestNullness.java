// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParser.*;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.NullnessConfig;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * {@code [nullness]}: the table, and the three jars it brings. A module that declares one of them
 * itself keeps its own version.
 */
@NullMarked
public final class ManifestNullness {

    private ManifestNullness() {}

    public static final String TABLE = "nullness";

    /** The keys {@code [nullness]} may carry; the schema names exactly these. */
    public static final List<String> KEYS = List.of("include", "exclude", "explicit", "severity", "options", "tests");

    /** The annotations the checked code writes, on the compile classpath. */
    public static final String JSPECIFY = "org.jspecify:jspecify:1.0.1";

    /** The javac plugin NullAway runs inside, on the processor path. */
    public static final String ERROR_PRONE = "com.google.errorprone:error_prone_core:2.51.0";

    /** The checker itself, on the processor path. */
    public static final String NULLAWAY = "com.uber.nullaway:nullaway:0.14.3";

    /** {@code [nullness]} as written; empty when the table is absent. */
    static Optional<NullnessConfig> parse(TomlTable root) {
        Object raw = root.get(List.of(TABLE));
        if (raw == null) return Optional.empty();
        if (!(raw instanceof TomlTable table)) {
            throw new JkBuildParseException("[nullness] must be a table: [nullness] include = [\"com.example\"]");
        }
        for (String key : table.keySet()) {
            if (!KEYS.contains(key)) {
                throw new JkBuildParseException(
                        "[nullness] unknown key `" + key + "` — expected one of: " + String.join(", ", KEYS));
            }
        }
        List<String> include = table.contains("include") ? packages(table, "include") : null;
        List<String> exclude = packages(table, "exclude");
        boolean explicit = bool(table, "explicit");
        NullnessConfig.Severity severity = NullnessConfig.Severity.ERROR;
        Object rawSeverity = table.get(List.of("severity"));
        if (rawSeverity != null) {
            if (!(rawSeverity instanceof String s)) {
                throw new JkBuildParseException("[nullness].severity must be \"error\" or \"warn\"");
            }
            severity = switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "error" -> NullnessConfig.Severity.ERROR;
                case "warn" -> NullnessConfig.Severity.WARN;
                default ->
                    throw new JkBuildParseException(
                            "[nullness].severity must be \"error\" or \"warn\", got `" + s + "`");
            };
        }
        List<String> options = strings(table.get(List.of("options")), "options");
        boolean tests = bool(table, "tests");
        return Optional.of(new NullnessConfig(include, exclude, explicit, severity, options, tests));
    }

    /**
     * {@code deps} with the jars {@code [nullness]} brings: JSpecify on the compile classpath unless
     * any scope declares it, Error Prone and NullAway on the processor path unless that path does.
     */
    static JkBuild.Dependencies withImplied(JkBuild.Dependencies deps) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        deps.byScope().forEach(byScope::put);
        boolean jspecifyDeclared = deps.byScope().values().stream()
                .flatMap(List::stream)
                .anyMatch(d -> moduleOf(JSPECIFY).equals(d.module()));
        if (!jspecifyDeclared) add(byScope, Scope.MAIN, JSPECIFY);
        add(byScope, Scope.PROCESSOR, ERROR_PRONE);
        add(byScope, Scope.PROCESSOR, NULLAWAY);
        return new JkBuild.Dependencies(byScope);
    }

    private static void add(EnumMap<Scope, List<Dependency>> byScope, Scope scope, String coordinate) {
        List<Dependency> list = new ArrayList<>(byScope.getOrDefault(scope, List.of()));
        String module = moduleOf(coordinate);
        if (list.stream().anyMatch(d -> module.equals(d.module()))) return;
        String version = coordinate.substring(module.length() + 1);
        list.add(new Dependency(module, VersionSelector.parse(version)).withImpliedBy(TABLE));
        byScope.put(scope, List.copyOf(list));
    }

    /** {@code group:artifact} of a {@code group:artifact:version} coordinate. */
    private static String moduleOf(String coordinate) {
        return coordinate.substring(0, coordinate.lastIndexOf(':'));
    }

    /** A list of package names, one per entry. */
    private static List<String> packages(TomlTable table, String key) {
        List<String> packages = strings(table.get(List.of(key)), key);
        for (String p : packages) {
            if (p.isBlank() || p.contains(",")) {
                throw new JkBuildParseException("[nullness]." + key + " lists one package per entry: " + key
                        + " = [\"com.example\", \"org.acme\"]");
            }
        }
        return packages;
    }

    private static boolean bool(TomlTable table, String key) {
        Object raw = table.get(List.of(key));
        if (raw == null) return false;
        if (!(raw instanceof Boolean b))
            throw new JkBuildParseException("[nullness]." + key + " must be true or false");
        return b;
    }

    private static List<String> strings(@Nullable Object raw, String key) {
        if (raw == null) return List.of();
        if (!(raw instanceof TomlArray arr)) {
            throw new JkBuildParseException("[nullness]." + key + " must be an array of strings");
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof String s)) {
                throw new JkBuildParseException("[nullness]." + key + " must be an array of strings");
            }
            out.add(s.trim());
        }
        return List.copyOf(out);
    }
}
