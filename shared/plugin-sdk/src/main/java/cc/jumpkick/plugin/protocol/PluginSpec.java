// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.ProjectFacts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The parsed engine→plugin spec: one JSONL reader for every plugin, replacing the per-plugin
 * KEY-value/tab parsers. It decodes the {@link PluginProtocol} spec vocabulary into typed
 * accessors; each op reads only the fields it declared. Unknown line types and config kinds are
 * ignored (forward-compat).
 */
public final class PluginSpec {

    private String op = "";
    private @Nullable String name; // step/command name from the op line
    private String pluginId = "";
    private final Map<String, Object> config = new LinkedHashMap<>();
    private @Nullable ProjectFacts project;
    private final Map<String, String> manifest = new LinkedHashMap<>();
    private @Nullable Path classesDir, sourceOutput, moduleDir, scratch, workdir, snapshotDir;
    private @Nullable Path javaHome, artifactPath;
    private final List<Path> compileClasspath = new ArrayList<>();
    private final List<Path> compilerClasspath = new ArrayList<>();
    private final List<Path> processorClasspath = new ArrayList<>();
    private final List<Path> friendPaths = new ArrayList<>();
    private final List<Path> runtimeClasspath = new ArrayList<>();
    private final List<PackageIo.RuntimeEntry> entries = new ArrayList<>();
    private final List<Path> sources = new ArrayList<>();
    private final List<String> args = new ArrayList<>();
    private final List<CompilerPlugin> compilerPlugins = new ArrayList<>();
    private final Map<String, Path> stepOutputs = new LinkedHashMap<>();
    private final Map<String, Path> extras = new LinkedHashMap<>();
    private final Map<Path, Path> classpathAnalyses = new LinkedHashMap<>();
    private final Map<String, String> secrets = new LinkedHashMap<>();
    private final List<String> commandArgs = new ArrayList<>();
    private boolean offline = true;

    private PluginSpec() {}

    /** A typed Kotlin compiler-plugin entry: coordinate id, jar path, and its options. */
    public record CompilerPlugin(String id, Path jar, List<String> options) {}

    public static PluginSpec read(Path file) throws IOException {
        PluginSpec s = new PluginSpec();
        String group = "", pname = "", version = "";
        @Nullable String mainClass = null;
        int javaRelease = 0;
        boolean nativeDeclared = false, kotlin = false;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            switch (String.valueOf(Jsonl.str(line, PluginProtocol.T))) {
                case PluginProtocol.OP -> {
                    s.op = requiredString(line, PluginProtocol.OP_NAME);
                    s.name = Jsonl.str(line, PluginProtocol.NAME);
                    s.pluginId = requiredString(line, PluginProtocol.PLUGIN);
                }
                case PluginProtocol.CONFIG -> {
                    String key = requiredString(line, PluginProtocol.KEY);
                    switch (String.valueOf(Jsonl.str(line, PluginProtocol.CONFIG_KIND))) {
                        case PluginProtocol.KIND_STRING ->
                            s.config.put(key, requiredString(line, PluginProtocol.VALUE));
                        case PluginProtocol.KIND_BOOL ->
                            s.config.put(key, Jsonl.bool(line, PluginProtocol.VALUE, false));
                        case PluginProtocol.KIND_INT ->
                            s.config.put(key, Jsonl.longValue(line, PluginProtocol.VALUE, 0));
                        case PluginProtocol.KIND_LIST -> s.config.put(key, Jsonl.strArray(line, PluginProtocol.VALUES));
                        default -> {
                            // unknown kind — forward-compat
                        }
                    }
                }
                case PluginProtocol.PROJECT -> {
                    group = requiredString(line, "group");
                    pname = requiredString(line, "name");
                    version = requiredString(line, "version");
                    javaRelease = Jsonl.intValue(line, "javaRelease", 0);
                    mainClass = Jsonl.str(line, "mainClass");
                    nativeDeclared = Jsonl.bool(line, "nativeDeclared", false);
                    kotlin = Jsonl.bool(line, "kotlin", false);
                }
                case PluginProtocol.MANIFEST_ATTR ->
                    s.manifest.put(
                            requiredString(line, PluginProtocol.KEY), requiredString(line, PluginProtocol.VALUE));
                case PluginProtocol.LAYOUT -> {
                    s.classesDir = path(Jsonl.str(line, "classesDir"));
                    s.sourceOutput = path(Jsonl.str(line, "sourceOutput"));
                    s.moduleDir = path(Jsonl.str(line, "moduleDir"));
                    s.scratch = path(Jsonl.str(line, "scratch"));
                    s.workdir = path(Jsonl.str(line, "workdir"));
                    s.snapshotDir = path(Jsonl.str(line, "snapshotDir"));
                }
                case PluginProtocol.JAVA_HOME -> s.javaHome = requiredPath(line, PluginProtocol.PATH);
                case PluginProtocol.ARTIFACT -> s.artifactPath = requiredPath(line, PluginProtocol.PATH);
                case PluginProtocol.CP -> {
                    Path p = requiredPath(line, PluginProtocol.PATH);
                    switch (String.valueOf(Jsonl.str(line, PluginProtocol.ROLE))) {
                        case PluginProtocol.ROLE_PROCESSOR -> s.processorClasspath.add(p);
                        case PluginProtocol.ROLE_FRIEND -> s.friendPaths.add(p);
                        case PluginProtocol.ROLE_RUNTIME -> s.runtimeClasspath.add(p);
                        case PluginProtocol.ROLE_COMPILER -> s.compilerClasspath.add(p);
                        default -> s.compileClasspath.add(p); // compile is the default role
                    }
                }
                case PluginProtocol.CP_ANALYSIS ->
                    s.classpathAnalyses.put(
                            requiredPath(line, PluginProtocol.PATH), requiredPath(line, PluginProtocol.ANALYSIS));
                case PluginProtocol.ENTRY -> {
                    @Nullable String jar = Jsonl.str(line, PluginProtocol.PATH);
                    @Nullable String container = Jsonl.str(line, PluginProtocol.CONTAINER);
                    @Nullable String g = Jsonl.str(line, "group");
                    @Nullable String a = Jsonl.str(line, "artifact");
                    @Nullable String v = Jsonl.str(line, "version");
                    s.entries.add(new PackageIo.RuntimeEntry(
                            requiredString(line, PluginProtocol.FILE_NAME),
                            jar == null ? null : Path.of(jar),
                            Jsonl.bool(line, PluginProtocol.SNAPSHOT, false),
                            container == null ? null : Path.of(container),
                            g == null ? "" : g,
                            a == null ? "" : a,
                            v == null ? "" : v));
                }
                case PluginProtocol.SOURCE -> s.sources.add(requiredPath(line, PluginProtocol.PATH));
                case PluginProtocol.ARG -> s.args.add(requiredString(line, PluginProtocol.VALUE));
                case PluginProtocol.COMPILER_PLUGIN ->
                    s.compilerPlugins.add(new CompilerPlugin(
                            requiredString(line, "id"),
                            requiredPath(line, PluginProtocol.PATH),
                            Jsonl.strArray(line, "options")));
                case PluginProtocol.STEP_OUTPUT ->
                    s.stepOutputs.put(
                            requiredString(line, PluginProtocol.NAME), requiredPath(line, PluginProtocol.DIR));
                case PluginProtocol.EXTRA ->
                    s.extras.put(requiredString(line, PluginProtocol.NAME), requiredPath(line, PluginProtocol.PATH));
                case PluginProtocol.SECRET ->
                    s.secrets.put(requiredString(line, PluginProtocol.KEY), requiredString(line, PluginProtocol.VALUE));
                case PluginProtocol.COMMAND_ARGS -> s.commandArgs.addAll(Jsonl.strArray(line, PluginProtocol.VALUES));
                case PluginProtocol.OFFLINE -> s.offline = Jsonl.bool(line, PluginProtocol.VALUE, true);
                default -> {
                    // unknown line — forward-compat
                }
            }
        }
        s.project = new ProjectFacts(group, pname, version, javaRelease, mainClass, nativeDeclared, kotlin, s.manifest);
        return s;
    }

    private static @Nullable Path path(@Nullable String s) {
        return s == null ? null : Path.of(s);
    }

    private static String requiredString(String line, String key) {
        return Objects.requireNonNull(Jsonl.str(line, key), "spec line missing " + key);
    }

    private static Path requiredPath(String line, String key) {
        return Path.of(requiredString(line, key));
    }

    public String op() {
        return op;
    }

    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    public String pluginId() {
        return pluginId;
    }

    public PluginConfig config() {
        return new PluginConfig(pluginId, config);
    }

    public ProjectFacts project() {
        return Objects.requireNonNull(project, "spec project facts not initialized");
    }

    public @Nullable Path classesDir() {
        return classesDir;
    }

    /**
     * The invariant every {@code compile} op shares: the spec must name a classes dir, a
     * {@code jvmTarget}, and at least one source. Returns the jvmTarget so a language worker
     * validates and reads it in one call, and so the three messages are written once instead of
     * once per language. What each worker decodes <em>after</em> this genuinely differs — kotlinc
     * takes friend paths, a module name and a {@code -jdk-home}; groovyc takes joint-mode stubs and
     * Java source roots and no project JDK at all — and stays in the worker's own spec type.
     */
    public String requireCompileInputs() {
        if (classesDir == null) throw new IllegalArgumentException("spec missing layout.classesDir (OUTPUT)");
        @Nullable String jvmTarget = config().stringOpt("jvmTarget").orElse(null);
        if (jvmTarget == null) throw new IllegalArgumentException("spec missing config jvmTarget");
        if (sources.isEmpty()) throw new IllegalArgumentException("spec has no source entries");
        return jvmTarget;
    }

    public @Nullable Path sourceOutput() {
        return sourceOutput;
    }

    public @Nullable Path moduleDir() {
        return moduleDir;
    }

    public @Nullable Path scratch() {
        return scratch;
    }

    public @Nullable Path workdir() {
        return workdir;
    }

    public @Nullable Path snapshotDir() {
        return snapshotDir;
    }

    public @Nullable Path javaHome() {
        return javaHome;
    }

    public @Nullable Path artifactPath() {
        return artifactPath;
    }

    public List<Path> compileClasspath() {
        return compileClasspath;
    }

    /**
     * Compile-classpath entries another jk compile produced, each with its producer's Zinc analysis
     * file ({@link PluginProtocol#CP_ANALYSIS}); empty when no entry has one.
     */
    public Map<Path, Path> classpathAnalyses() {
        return classpathAnalyses;
    }

    /** Scala compiler + bridge jars for mixed compile; empty on Java-only. */
    public List<Path> compilerClasspath() {
        return compilerClasspath;
    }

    public List<Path> processorClasspath() {
        return processorClasspath;
    }

    public List<Path> friendPaths() {
        return friendPaths;
    }

    public List<Path> runtimeClasspath() {
        return runtimeClasspath;
    }

    public List<PackageIo.RuntimeEntry> entries() {
        return entries;
    }

    public List<Path> sources() {
        return sources;
    }

    public List<String> args() {
        return args;
    }

    public List<CompilerPlugin> compilerPlugins() {
        return compilerPlugins;
    }

    public Optional<Path> stepOutput(String step) {
        return Optional.ofNullable(stepOutputs.get(step));
    }

    public Optional<Path> extra(String name) {
        return Optional.ofNullable(extras.get(name));
    }

    public Optional<String> secret(String key) {
        return Optional.ofNullable(secrets.get(key));
    }

    public List<String> commandArgs() {
        return commandArgs;
    }

    /**
     * Whether this job forbids network access — the engine's {@code --offline}, stamped onto the
     * spec at the fork. True when the spec carries no {@link PluginProtocol#OFFLINE} line: a worker
     * launched without a stated policy must not reach out.
     */
    public boolean offline() {
        return offline;
    }
}
