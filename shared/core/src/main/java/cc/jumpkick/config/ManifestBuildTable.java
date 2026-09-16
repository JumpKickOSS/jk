// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.DebugInfo;
import cc.jumpkick.model.JkBuild;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * The {@code [build]} and {@code [test]} tables: the module-local compile and test settings that
 * never reach a classpath or the lockfile. Either table may appear alone.
 */
@NullMarked
final class ManifestBuildTable {

    private ManifestBuildTable() {}

    /** Read both tables, {@code [test]} overriding the worker pins {@code [build]} set. */
    static Settings read(@Nullable TomlTable build, @Nullable TomlTable test) {
        Settings s = new Settings();
        if (build != null) readBuildTable(build, s);
        if (test != null) readTestTable(test, s);
        return s;
    }

    /** The settings as the two tables fill them in, {@code [build]} first. */
    static final class Settings {
        final List<String> orderAfter = new ArrayList<>();
        final List<String> testPluginJars = new ArrayList<>();
        boolean lint = true;
        DebugInfo debug = DebugInfo.FULL;
        final List<String> kspOptions = new ArrayList<>();
        final List<String> extraSrc = new ArrayList<>();
        final List<String> testExtraSrc = new ArrayList<>();

        @Nullable
        String fixtures;

        @Nullable
        Integer testWorkers;

        final List<String> testSerialTags = new ArrayList<>();
        boolean testAssertions = true;
        boolean testCoverage = false;
        final List<String> testTools = new ArrayList<>();
    }

    /** The keys {@code [build]} may carry; {@code logic} is read by {@link BuildLogicToml}. */
    public static final List<String> BUILD_KEYS = List.of(
            "order-after",
            "test-plugin-jars",
            "lint",
            "debug",
            "ksp-options",
            "extra-src",
            "test-workers",
            "test-parallel",
            "logic");

    private static void readBuildTable(TomlTable build, Settings s) {
        if (build.contains("extra-resources")) {
            throw new JkBuildParseException("[build].extra-resources is not a setting — a plugin"
                    + " worker ships its own jk-plugin.toml at the jar root (module-root"
                    + " jk-plugin.toml is copied there automatically; src/main/resources/"
                    + "jk-plugin.toml already is). Modules cannot pull files from other modules.");
        }
        for (String key : build.keySet()) {
            if (!BUILD_KEYS.contains(key)) {
                throw new JkBuildParseException(
                        "[build] unknown key `" + key + "` — expected one of: " + String.join(", ", BUILD_KEYS));
            }
        }
        TomlArray arr = build.getArray("order-after");
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                Object val = arr.get(i);
                if (!(val instanceof String str))
                    throw new JkBuildParseException("[build].order-after must be an array of strings");
                if (!str.isBlank()) s.orderAfter.add(str);
            }
        }
        TomlArray twj = build.getArray("test-plugin-jars");
        if (twj != null) {
            for (int i = 0; i < twj.size(); i++) {
                Object val = twj.get(i);
                if (!(val instanceof String str))
                    throw new JkBuildParseException("[build].test-plugin-jars must be an array of strings");
                if (!str.isBlank()) s.testPluginJars.add(str);
            }
        }
        // `lint` defaults on (surface deprecation/unchecked); `lint = false`
        // suppresses jk's default javac lint flags for users who don't want it.
        s.lint = !Boolean.FALSE.equals(build.getBoolean("lint"));
        // `debug` is the javac debug-info level; absent means full, as Gradle and Maven compile.
        Object rawDebug = build.get("debug");
        if (rawDebug != null) {
            if (!(rawDebug instanceof String level)) {
                throw new JkBuildParseException("[build].debug must be a string: full, lines or none");
            }
            try {
                s.debug = DebugInfo.parse(level);
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException("[build].debug: " + e.getMessage());
            }
        }
        // [build] ksp-options — project-declared KSP processor options (`key=value`; Room's
        // room.schemaLocation is the canonical consumer). Plugin manifests contribute theirs
        // via [[contribute.compiler-args]] ksp; this is the project-owned lane.
        TomlArray kspOpts = build.getArray("ksp-options");
        if (kspOpts != null) {
            for (int i = 0; i < kspOpts.size(); i++) {
                Object val = kspOpts.get(i);
                if (!(val instanceof String str) || str.isBlank() || !str.contains("=")) {
                    throw new JkBuildParseException("[build].ksp-options must be an array of key=value strings");
                }
                s.kspOptions.add(str);
            }
        }
        // [build] extra-src — additional module-relative source roots (variant overlays append).
        TomlArray es = build.getArray("extra-src");
        if (es != null) {
            for (int i = 0; i < es.size(); i++) {
                Object val = es.get(i);
                if (!(val instanceof String str) || str.isBlank())
                    throw new JkBuildParseException("[build].extra-src must be an array of directory strings");
                s.extraSrc.add(str);
            }
        }
        // [build] test-workers — pin within-module test JVMs (1 = serial; 0 = same as omitting: the
        // build's auto share).
        // [build] test-parallel = false is an alias for test-workers = 1 (Mill testParallelism=false).
        if (build.contains("test-workers")) {
            Long n = build.getLong("test-workers");
            if (n == null) throw new JkBuildParseException("[build].test-workers must be an integer >= 0");
            if (n < 0) throw new JkBuildParseException("[build].test-workers must be >= 0");
            s.testWorkers = n.intValue();
        }
        if (Boolean.FALSE.equals(build.getBoolean("test-parallel"))) {
            s.testWorkers = 1;
        }
    }

    private static void readTestTable(TomlTable test, Settings s) {
        if (test.contains("workers")) {
            Long n = test.getLong("workers");
            if (n == null) throw new JkBuildParseException("[test].workers must be an integer >= 0");
            if (n < 0) throw new JkBuildParseException("[test].workers must be >= 0");
            s.testWorkers = n.intValue();
        }
        if (Boolean.FALSE.equals(test.getBoolean("parallel"))) {
            s.testWorkers = 1;
        }
        // [test] extra-src — extra test-tier sources compiled into test classes. A single file
        // is legal where a directory would over-reach (clients/cli compiling one IntelliJ
        // parser type). Shared helpers a sibling consumes belong in [test] fixtures.
        TomlArray tes = test.getArray("extra-src");
        if (tes != null) {
            for (int i = 0; i < tes.size(); i++) {
                Object val = tes.get(i);
                if (!(val instanceof String str) || str.isBlank())
                    throw new JkBuildParseException("[test].extra-src must be an array of directory or file paths");
                s.testExtraSrc.add(str);
            }
        }
        // [test] fixtures — a source root compiled to target/test-fixtures/classes, never an
        // artifact. `true` means src/fixtures/java; a string names the root.
        if (test.contains("fixtures")) {
            Object raw = test.get("fixtures");
            if (raw instanceof Boolean flag) {
                s.fixtures = flag ? JkBuild.Build.DEFAULT_FIXTURES : null;
            } else if (raw instanceof String path) {
                if (path.isBlank()) {
                    throw new JkBuildParseException(
                            "[test].fixtures must be `true` or a non-empty module-relative directory");
                }
                s.fixtures = path;
            } else {
                throw new JkBuildParseException(
                        "[test].fixtures must be `true` or a non-empty module-relative directory");
            }
        }
        // [test] serial-tags — class-level tags that never share the sharded worker pool.
        TomlArray st = test.getArray("serial-tags");
        if (st != null) {
            for (int i = 0; i < st.size(); i++) {
                Object val = st.get(i);
                if (!(val instanceof String str))
                    throw new JkBuildParseException("[test].serial-tags must be an array of tag strings");
                if (!str.isBlank()) s.testSerialTags.add(str);
            }
        }
        // [test] assertions — -ea on every forked test JVM unless the module turns it off.
        if (test.contains("assertions")) {
            if (!(test.get("assertions") instanceof Boolean assertions)) {
                throw new JkBuildParseException("[test].assertions must be true or false");
            }
            s.testAssertions = assertions;
        }
        // [test] coverage — every test JVM of this module runs under the JaCoCo agent and the
        // module leaves its coverage report, as `jk test --coverage` does for the whole run.
        if (test.contains("coverage")) {
            if (!(test.get("coverage") instanceof Boolean coverage)) {
                throw new JkBuildParseException("[test].coverage must be true or false");
            }
            s.testCoverage = coverage;
        }
        // [test] tools — external executables the suite shells out to, by the bare name the tests
        // invoke; each one's PATH location and --version become run-tests inputs. A path is refused:
        // the tests resolve the name on PATH, and so must the stamp, or the two would disagree.
        TomlArray tools = test.getArray("tools");
        if (tools != null) {
            for (int i = 0; i < tools.size(); i++) {
                Object val = tools.get(i);
                if (!(val instanceof String str) || str.isBlank()) {
                    throw new JkBuildParseException(
                            "[test].tools must be an array of executable names: tools = [\"node\"]");
                }
                if (str.contains("/") || str.contains("\\")) {
                    throw new JkBuildParseException(
                            "[test].tools names an executable on PATH, not a path: `" + str + "`");
                }
                if (!s.testTools.contains(str)) s.testTools.add(str);
            }
        }
    }
}
