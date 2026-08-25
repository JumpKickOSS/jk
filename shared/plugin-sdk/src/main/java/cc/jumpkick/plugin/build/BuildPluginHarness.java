// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The worker-side driver every build plugin delegates its {@code Plugin.run} to: reads the
 * engine's spec file, replays {@link BuildPlugin#register} against a recording context, then
 * answers the requested op —
 *
 * <ul>
 *   <li>{@code describe} — emit the registered step/packager declarations (the engine caches
 *       these per config+facts, so fully-cached builds never fork the worker);
 *   <li>{@code run-step <name>} — execute one step body against the spec's resolved inputs;
 *   <li>{@code package} — execute the packager body.
 * </ul>
 *
 * <p>Spec lines are flat JSONL objects ({@code {"t":"config",…}}, {@code {"t":"cp",…}}, …);
 * replies ride the plugin's {@link ProtocolWriter} ({@code {"t":"label"}}, {@code {"t":"error"}},
 * {@code {"t":"step"}}/{@code {"t":"packager"}} for describe). The process exit code is the
 * terminal signal — there is no protocol end marker.
 */
public final class BuildPluginHarness {

    private BuildPluginHarness() {}

    /**
     * The worker entry point every plugin's {@code Plugin.run} delegates to. A plugin either
     * implements {@link BuildPlugin} directly (the low-level escape hatch — Android) or one or more
     * capability interfaces ({@link BuildExtension}/{@link PackageExtension}/…); either way this
     * replays its registration and answers the requested op.
     */
    public static int run(cc.jumpkick.plugin.Plugin plugin, List<String> args, ProtocolWriter out)
            throws Exception {
        return run(asBuildPlugin(plugin), args, out);
    }

    /**
     * Adapt a capability plugin to the low-level {@link BuildPlugin} substrate: a {@code register()}
     * that drives each implemented capability against a phase-scoped context, recording the same
     * {@link TaskSpec}/{@link PackagerSpec} declarations. A plugin that implements {@link BuildPlugin}
     * directly is returned unchanged.
     */
    static BuildPlugin asBuildPlugin(cc.jumpkick.plugin.Plugin plugin) {
        if (plugin instanceof BuildPlugin bp) {
            return bp;
        }
        return ctx -> {
            String id = plugin.id();
            try {
                if (plugin instanceof ResolveExtension e) {
                    e.resolve(new DefaultTaskContext(ctx, id));
                }
                if (plugin instanceof BuildExtension e) {
                    e.build(new DefaultTaskContext(ctx, id));
                }
                if (plugin instanceof TestExtension e) {
                    e.test(new DefaultTaskContext(ctx, id));
                }
                if (plugin instanceof PackageExtension e) {
                    e.pack(new DefaultPackageContext(ctx));
                }
            } catch (RuntimeException | Error ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            if (plugin instanceof RunExtension || plugin instanceof ImageExtension
                    || plugin instanceof PublishExtension) {
                throw new IllegalStateException("terminal-target capability (run/image/publish) on plugin `" + id
                        + "` runs via its own worker entry, not the build harness");
            }
        };
    }

    // Package-private: the substrate path. Real plugins reach it through run(Plugin, …); same-package
    // tests may drive a bare BuildPlugin fixture directly.
    static int run(BuildPlugin plugin, List<String> args, ProtocolWriter out) throws Exception {
        if (args.isEmpty()) {
            System.err.println("build-plugin worker: expected spec file path as first argument");
            return 64;
        }
        Path specFile = Path.of(args.get(0));
        if (!Files.isRegularFile(specFile)) {
            System.err.println("build-plugin worker: spec file not found: " + specFile);
            return 66;
        }
        Spec spec = Spec.read(specFile);

        Recorder recorder = new Recorder(spec.config(), spec.project());
        plugin.register(recorder);

        switch (spec.op()) {
            case "describe" -> describe(recorder, out);
            case "run-step" -> {
                TaskSpec step = recorder.step(spec.stepName());
                if (step == null || step.body() == null) {
                    out.emit("{\"t\":\"error\",\"code\":\"unknown-step\",\"message\":"
                            + Jsonl.quote("no registered step named " + spec.stepName()) + "}");
                    return 65;
                }
                try {
                    step.body().run(new SpecTaskExec(spec, out));
                } catch (Exception e) {
                    out.emit("{\"t\":\"error\",\"code\":\"step-failed\",\"message\":"
                            + Jsonl.quote(String.valueOf(e.getMessage())) + "}");
                    return 1;
                }
            }
            case "command" -> {
                PluginCommandSpec command = recorder.command(spec.stepName());
                if (command == null || command.body() == null) {
                    out.emit("{\"t\":\"error\",\"code\":\"unknown-command\",\"message\":"
                            + Jsonl.quote("no registered command named " + spec.stepName()) + "}");
                    return 65;
                }
                try {
                    int exit = command.body().run(new SpecCommandExec(spec, out));
                            return exit;
                } catch (Exception e) {
                    out.emit("{\"t\":\"error\",\"code\":\"command-failed\",\"message\":"
                            + Jsonl.quote(String.valueOf(e.getMessage())) + "}");
                    return 1;
                }
            }
            case "package" -> {
                PackagerSpec packager = recorder.packager();
                if (packager == null || packager.body() == null) {
                    out.emit("{\"t\":\"error\",\"code\":\"no-packager\",\"message\":"
                            + Jsonl.quote("plugin registered no packager") + "}");
                    return 65;
                }
                try {
                    packager.body().produce(new SpecPackageIo(spec, out));
                } catch (Exception e) {
                    out.emit("{\"t\":\"error\",\"code\":\"package-failed\",\"message\":"
                            + Jsonl.quote(String.valueOf(e.getMessage())) + "}");
                    return 1;
                }
            }
            default -> {
                out.emit("{\"t\":\"error\",\"code\":\"unknown-op\",\"message\":" + Jsonl.quote(spec.op()) + "}");
                return 64;
            }
        }
        return 0;
    }

    private static void describe(Recorder recorder, ProtocolWriter out) {
        for (TaskSpec step : recorder.steps()) {
            StringBuilder b = new StringBuilder("{\"t\":\"task\",\"name\":")
                    .append(Jsonl.quote(step.name()))
                    .append(",\"requires\":")
                    .append(quoteArray(step.requires()))
                    .append(",\"inputs\":")
                    .append(quoteArray(step.declaredInputs().stream().map(In::wireName).toList()))
                    .append(",\"outputs\":")
                    .append(quoteArray(step.declaredOutputs()))
                    .append(",\"contributesClasses\":")
                    .append(quoteArray(step.classesContributions()))
                    .append(",\"contributesResources\":")
                    .append(quoteArray(step.resourcesContributions()))
                    .append(",\"contributesSources\":")
                    .append(quoteArray(step.sourcesContributions()))
                    .append(",\"contributesTestClasspath\":")
                    .append(quoteArray(step.testClasspathContributions()))
                    .append(",\"transformsClasses\":")
                    .append(Jsonl.quote(step.classesTransform() == null ? "" : step.classesTransform()))
                    .append(",\"stage\":")
                    .append(Jsonl.quote(step.stage() == null ? "" : step.stage()))
                    .append('}');
            out.emit(b.toString());
        }
        PackagerSpec packager = recorder.packager();
        if (packager != null) {
            out.emit("{\"t\":\"packager\",\"name\":" + Jsonl.quote(packager.name()) + ",\"inputs\":"
                    + quoteArray(packager.declaredInputs().stream().map(In::wireName).toList()) + "}");
        }
        for (PluginCommandSpec command : recorder.commands()) {
            out.emit("{\"t\":\"command\",\"name\":" + Jsonl.quote(command.name()) + ",\"description\":"
                    + Jsonl.quote(command.description()) + "}");
        }
    }

    private static String quoteArray(List<String> values) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) b.append(',');
            b.append(Jsonl.quote(values.get(i)));
        }
        return b.append(']').toString();
    }

    // ---- registration recording ----------------------------------------------------------

    private static final class Recorder implements BuildPluginContext {
        private final PluginConfig config;
        private final ProjectFacts project;
        private final List<TaskSpec> steps = new ArrayList<>();
        private final List<PluginCommandSpec> commands = new ArrayList<>();
        private PackagerSpec packager;

        Recorder(PluginConfig config, ProjectFacts project) {
            this.config = config;
            this.project = project;
        }

        @Override
        public PluginConfig config() {
            return config;
        }

        @Override
        public ProjectFacts project() {
            return project;
        }

        @Override
        public void task(TaskSpec spec) {
            steps.add(spec);
        }

        @Override
        public void packaging(PackagerSpec spec) {
            if (packager != null) {
                throw new IllegalStateException("one packager may replace the main artifact — a second ("
                        + spec.name() + ") conflicts with " + packager.name());
            }
            packager = spec;
        }

        List<TaskSpec> steps() {
            return steps;
        }

        TaskSpec step(String name) {
            for (TaskSpec s : steps) if (s.name().equals(name)) return s;
            return null;
        }

        PackagerSpec packager() {
            return packager;
        }

        @Override
        public void command(PluginCommandSpec spec) {
            commands.add(spec);
        }

        List<PluginCommandSpec> commands() {
            return commands;
        }

        PluginCommandSpec command(String name) {
            for (PluginCommandSpec v : commands) if (v.name().equals(name)) return v;
            return null;
        }
    }

    // ---- the engine's spec, decoded --------------------------------------------------------

    record Spec(
            String op,
            String stepName,
            PluginConfig config,
            ProjectFacts project,
            Path classesDir,
            Path moduleDir,
            Path scratch,
            Path javaHome,
            Path artifactPath,
            List<Path> classpath,
            List<PackageIo.RuntimeEntry> entries,
            Map<String, Path> stepOutputs,
            Map<String, Path> extras,
            List<String> commandArgs,
            Map<String, String> secrets,
            boolean offline) {

        static Spec read(Path file) throws IOException {
            String op = "";
            String stepName = null;
            String pluginId = "";
            Map<String, Object> configValues = new LinkedHashMap<>();
            String group = "";
            String name = "";
            String version = "";
            int javaRelease = 0;
            String mainClass = null;
            boolean nativeDeclared = false;
            boolean kotlin = false;
            Map<String, String> manifest = new LinkedHashMap<>();
            Path classesDir = null;
            Path moduleDir = null;
            Path scratch = null;
            Path javaHome = null;
            Path artifactPath = null;
            List<Path> classpath = new ArrayList<>();
            List<PackageIo.RuntimeEntry> entries = new ArrayList<>();
            Map<String, Path> stepOutputs = new LinkedHashMap<>();
            Map<String, Path> extras = new LinkedHashMap<>();
            List<String> commandArgs = new ArrayList<>();
            Map<String, String> secrets = new LinkedHashMap<>();
            // Absent means offline: a worker launched without a stated policy must not reach out.
            boolean offline = true;

            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                switch (String.valueOf(Jsonl.str(line, "t"))) {
                    case "op" -> {
                        op = String.valueOf(Jsonl.str(line, "op"));
                        stepName = Jsonl.str(line, PluginProtocol.NAME);
                        pluginId = String.valueOf(Jsonl.str(line, "plugin"));
                    }
                    case "config" -> {
                        String key = Jsonl.str(line, "key");
                        switch (String.valueOf(Jsonl.str(line, "kind"))) {
                            case "string" -> configValues.put(key, Jsonl.str(line, "value"));
                            case "bool" -> configValues.put(key, Jsonl.bool(line, "value", false));
                            case "int" -> configValues.put(key, Jsonl.longValue(line, "value", 0));
                            case "list" -> configValues.put(key, Jsonl.strArray(line, "values"));
                            default -> {
                                // unknown kind — treat as absent (forward compatibility)
                            }
                        }
                    }
                    case "project" -> {
                        group = String.valueOf(Jsonl.str(line, "group"));
                        name = String.valueOf(Jsonl.str(line, "name"));
                        version = String.valueOf(Jsonl.str(line, "version"));
                        javaRelease = Jsonl.intValue(line, "javaRelease", 0);
                        mainClass = Jsonl.str(line, "mainClass");
                        nativeDeclared = Jsonl.bool(line, "nativeDeclared", false);
                        kotlin = Jsonl.bool(line, "kotlin", false);
                    }
                    case "manifest-attr" -> manifest.put(Jsonl.str(line, "key"), Jsonl.str(line, "value"));
                    case "layout" -> {
                        String classes = Jsonl.str(line, "classesDir");
                        if (classes != null) classesDir = Path.of(classes);
                        String module = Jsonl.str(line, "moduleDir");
                        if (module != null) moduleDir = Path.of(module);
                        String scratchDir = Jsonl.str(line, "scratch");
                        if (scratchDir != null) scratch = Path.of(scratchDir);
                    }
                    case "java-home" -> javaHome = Path.of(String.valueOf(Jsonl.str(line, "path")));
                    case "artifact" -> artifactPath = Path.of(String.valueOf(Jsonl.str(line, "path")));
                    case "cp" -> classpath.add(Path.of(String.valueOf(Jsonl.str(line, "path"))));
                    case "entry" -> {
                        String jarPath = Jsonl.str(line, "path");
                        String container = Jsonl.str(line, "container");
                        String g = Jsonl.str(line, "group");
                        String a = Jsonl.str(line, "artifact");
                        String v = Jsonl.str(line, "version");
                        entries.add(new PackageIo.RuntimeEntry(
                                String.valueOf(Jsonl.str(line, "file")),
                                jarPath == null ? null : Path.of(jarPath),
                                Jsonl.bool(line, "snapshot", false),
                                container == null ? null : Path.of(container),
                                g == null ? "" : g,
                                a == null ? "" : a,
                                v == null ? "" : v));
                    }
                    case "step-output" ->
                        stepOutputs.put(
                                String.valueOf(Jsonl.str(line, "name")),
                                Path.of(String.valueOf(Jsonl.str(line, "dir"))));
                    case "command-args" -> commandArgs.addAll(Jsonl.strArray(line, "values"));
                    case "extra" ->
                        extras.put(
                                String.valueOf(Jsonl.str(line, "name")),
                                Path.of(String.valueOf(Jsonl.str(line, "path"))));
                    case "secret" ->
                        secrets.put(
                                String.valueOf(Jsonl.str(line, "key")),
                                String.valueOf(Jsonl.str(line, "value")));
                    case PluginProtocol.OFFLINE -> offline = Jsonl.bool(line, PluginProtocol.VALUE, true);
                    default -> {
                        // unknown line — forward compatibility
                    }
                }
            }
            PluginConfig config = new PluginConfig(pluginId, configValues);
            ProjectFacts facts =
                    new ProjectFacts(group, name, version, javaRelease, mainClass, nativeDeclared, kotlin, manifest);
            return new Spec(
                    op, stepName, config, facts, classesDir, moduleDir, scratch, javaHome, artifactPath, classpath,
                    entries, stepOutputs, extras, commandArgs, secrets, offline);
        }
    }

    // ---- exec facades over the spec ---------------------------------------------------------

    private record SpecTaskExec(Spec spec, ProtocolWriter out) implements TaskExec {
        @Override
        public Path classesDir() {
            return spec.classesDir();
        }

        @Override
        public List<Path> runtimeClasspath() {
            return spec.classpath();
        }

        @Override
        public List<PackageIo.RuntimeEntry> runtimeEntries() {
            return spec.entries();
        }

        @Override
        public PluginConfig config() {
            return spec.config();
        }

        @Override
        public ProjectFacts project() {
            return spec.project();
        }

        @Override
        public Path moduleDir() {
            return spec.moduleDir();
        }

        @Override
        public Path scratch() {
            return spec.scratch();
        }

        @Override
        public Path javaHome() {
            return spec.javaHome();
        }

        @Override
        public boolean offline() {
            return spec.offline();
        }

        @Override
        public Optional<Path> extra(String name) {
            return Optional.ofNullable(spec.extras().get(name));
        }

        @Override
        public Optional<Path> stepOutput(String step) {
            return Optional.ofNullable(spec.stepOutputs().get(step));
        }

        @Override
        public void label(String text) {
            out.emit("{\"t\":\"label\",\"text\":" + Jsonl.quote(text) + "}");
        }
    }

    private record SpecCommandExec(Spec spec, ProtocolWriter out) implements PluginCommandExec {
        @Override
        public List<String> args() {
            return spec.commandArgs();
        }

        @Override
        public PluginConfig config() {
            return spec.config();
        }

        @Override
        public ProjectFacts project() {
            return spec.project();
        }

        @Override
        public Path moduleDir() {
            return spec.moduleDir();
        }

        @Override
        public Optional<Path> extra(String name) {
            return Optional.ofNullable(spec.extras().get(name));
        }

        @Override
        public Optional<Path> mainArtifact() {
            return Optional.ofNullable(spec.artifactPath())
                    .filter(Files::isRegularFile);
        }

        /**
         * Loud rather than defaulted: a command spec with no {@code java-home} line is an engine
         * bug, and the wrong fallback ({@code System.getProperty("java.home")}) is invisible —
         * it silently runs the tool on the engine's floor JDK. Same reasoning as the fail-closed
         * {@code offline} default, opposite direction: there is no safe guess for a JDK.
         */
        @Override
        public Path javaHome() {
            Path home = spec.javaHome();
            if (home == null) {
                throw new IllegalStateException("this command's spec states no JDK — the engine writes it with"
                        + " SpecWriter.javaHome(). A command body must not fall back to the worker JVM's own"
                        + " java.home: that is the engine's floor JDK, not the project's pin.");
            }
            return home;
        }

        @Override
        public boolean offline() {
            return spec.offline();
        }

        @Override
        public void out(String line) {
            out.emit(cc.jumpkick.plugin.protocol.PluginReply.commandOut(line));
        }

        @Override
        public void label(String text) {
            out.emit("{\"t\":\"label\",\"text\":" + Jsonl.quote(text) + "}");
        }
    }

    private record SpecPackageIo(Spec spec, ProtocolWriter out) implements PackageIo {
        @Override
        public Path classesDir() {
            return spec.classesDir();
        }

        @Override
        public Path moduleDir() {
            return spec.moduleDir();
        }

        @Override
        public List<RuntimeEntry> runtimeEntries() {
            return spec.entries();
        }

        @Override
        public PluginConfig config() {
            return spec.config();
        }

        @Override
        public ProjectFacts project() {
            return spec.project();
        }

        @Override
        public Optional<Path> stepOutput(String step) {
            return Optional.ofNullable(spec.stepOutputs().get(step));
        }

        @Override
        public Optional<Path> extra(String name) {
            return Optional.ofNullable(spec.extras().get(name));
        }

        @Override
        public Path artifactPath() {
            return spec.artifactPath();
        }

        @Override
        public Optional<String> secret(String key) {
            return Optional.ofNullable(spec.secrets().get(key));
        }

        @Override
        public Path javaHome() {
            return spec.javaHome();
        }

        @Override
        public boolean offline() {
            return spec.offline();
        }

        @Override
        public void label(String text) {
            out.emit("{\"t\":\"label\",\"text\":" + Jsonl.quote(text) + "}");
        }

        @Override
        public void produced(Path path) {
            out.emit("{\"t\":\"produced\",\"path\":"
                    + Jsonl.quote(path.toAbsolutePath().normalize().toString()) + "}");
        }
    }
}
