// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.micronaut;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildContext;
import cc.jumpkick.plugin.build.BuildExtension;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.TaskExec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Micronaut build plugin: optional {@code micronaut-aot} step (JK-1536). Packaging stays
 * declarative ({@code [application] assembly = true}); AOT outputs contribute classes/resources
 * that the engine merges into package-jar / assembly.
 */
public final class MicronautPlugin implements Plugin, BuildExtension {

    static final String AOT_STEP = "micronaut-aot";
    static final String RUNTIME_JIT = "jit";
    static final String RUNTIME_NATIVE = "native";
    static final String DEFAULT_AOT_CONFIG = "aot.properties";
    private static final String AOT_TOOLS = "micronaut-aot-cli";
    private static final String AOT_MAIN = "io.micronaut.aot.cli.Main";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-micronaut", "##JKMN:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void build(BuildContext ctx) {
        PluginConfig cfg = ctx.config();
        // Explicit aot=true, or auto when [native] is declared (mirror spring-boot).
        if (cfg.bool("aot").orElse(ctx.project().nativeDeclared())) {
            // The properties file is read by the step body, so it belongs in the action key —
            // In.config() only covers the [micronaut] table. Declared whether or not it exists:
            // an absent declared file fingerprints distinctly, so creating one re-runs.
            ctx.named(AOT_STEP)
                    .inputs(In.classes(), In.runtimeClasspath(), In.config(), In.projectFiles(configSpec(cfg)))
                    .outputs("generated")
                    .contributesClasses("generated/classes")
                    .contributesResources("generated/resources")
                    .run(MicronautPlugin::runAot);
        }
    }

    private static void runAot(TaskExec exec) throws Exception {
        Path classes = exec.classesDir();
        if (!Files.isDirectory(classes)) {
            throw new IOException("no classes to optimize at " + classes);
        }

        // Unset follows the project: a [native] build wants native-oriented AOT, and getting JIT
        // optimizers into an image is what JK-1695 is about. An explicit value always wins.
        java.util.Optional<String> declared =
                exec.config().stringOpt("aot-runtime").filter(s -> !s.isBlank());
        String runtime = declared.map(s -> s.trim().toLowerCase(Locale.ROOT))
                .orElse(exec.project().nativeDeclared() ? RUNTIME_NATIVE : RUNTIME_JIT);
        if (!runtime.equals(RUNTIME_JIT) && !runtime.equals(RUNTIME_NATIVE)) {
            throw new IOException("[micronaut] aot-runtime must be \"jit\" or \"native\" (got `" + runtime + "`)");
        }
        if (declared.isPresent()
                && runtime.equals(RUNTIME_JIT)
                && exec.project().nativeDeclared()) {
            exec.label("note: aot-runtime=jit with [native] declared — the JIT optimizers are not"
                    + " native-oriented; remove the key to follow the project");
        }

        String pkg = exec.config()
                .stringOpt("aot-package")
                .filter(s -> !s.isBlank())
                .orElse(exec.project().group() + ".aot.generated");

        Path generated = exec.outputDir("generated");
        Path configFile = writeEffectiveConfig(exec, generated, runtime);

        // Tool CP: AOT cli closure (api + cli + std-optimizers via transitive step-dep).
        Path tools = exec.requireExtra(AOT_TOOLS);
        List<Path> toolCp = new ArrayList<>();
        if (Files.isDirectory(tools)) {
            toolCp.addAll(jarsIn(tools));
        } else if (Files.isRegularFile(tools)) {
            toolCp.add(tools);
        } else {
            throw new IOException("step-dependency `" + AOT_TOOLS + "` missing at " + tools);
        }

        // Optimizer argument classpath: AOT modules + app classes + runtime jars (Maven parity).
        List<Path> optimizerCp = new ArrayList<>(toolCp);
        optimizerCp.add(classes);
        optimizerCp.addAll(exec.runtimeClasspath());

        String cpArg = joinCp(optimizerCp);
        exec.label("Micronaut AOT (" + runtime + ", " + pkg + ")");

        TaskExec.ToolRun.Result run = exec.java()
                .classpath(toolCp)
                .mainClass(AOT_MAIN)
                .arg("--classpath=" + cpArg)
                .arg("--package=" + pkg)
                .arg("--runtime=" + runtime)
                .arg("--config=" + configFile.toAbsolutePath())
                .arg("--output=" + generated.toAbsolutePath())
                .cwd(exec.moduleDir())
                .run();
        if (run.exit() != 0) {
            throw new IOException("Micronaut AOT failed (exit " + run.exit() + "):\n" + tail(run.output()));
        }

        Path genClasses = generated.resolve("classes");
        if (!Files.isDirectory(genClasses)) {
            // Some AOT versions nest under generated/classes; also accept generated/generated/classes.
            Path nested = generated.resolve("generated").resolve("classes");
            if (Files.isDirectory(nested)) {
                // Normalize layout to contributed path generated/classes.
                Files.createDirectories(genClasses.getParent());
                copyTree(nested, genClasses);
            } else {
                throw new IOException("Micronaut AOT reported success but produced no classes under " + generated);
            }
        }
    }

    /**
     * Effective aot.properties: the user's file when present ({@code aot-config} or
     * {@code aot.properties}), else the defaults for {@code runtime}.
     */
    private static Path writeEffectiveConfig(TaskExec exec, Path generated, String runtime) throws IOException {
        Properties props = new Properties();
        Path user = userConfigFile(
                exec.moduleDir(), exec.config().stringOpt("aot-config").orElse(""));
        if (user != null) {
            try (var in = Files.newInputStream(user)) {
                props.load(in);
            }
        } else {
            defaultsFor(runtime).forEach(props::setProperty);
        }
        Path effective = generated.resolve("effective-aot.properties");
        Files.createDirectories(effective.getParent());
        Files.writeString(effective, renderProperties(props));
        return effective;
    }

    /**
     * Default optimizers for a runtime.
     *
     * <p>The two sets differ where an optimizer resolves state at build time. Under {@code jit}
     * that is the point: caching the environment and precomputing properties is free startup.
     * Under {@code native} it puts the resolved objects — {@code Inet4Address} among them — into
     * the image heap, and {@code native-image} refuses to build against types initialized at run
     * time. Service loading splits the same way: the JIT optimizer is the wrong one for a closed
     * world.
     *
     * <p>Source translation ({@code logback.xml}, YAML) helps both, and helps native twice over
     * by removing a by-name instantiation path nothing can otherwise see.
     */
    static java.util.Map<String, String> defaultsFor(String runtime) {
        java.util.Map<String, String> props = new java.util.LinkedHashMap<>();
        props.put("logback.xml.to.java.enabled", "true");
        props.put("yaml.to.java.config.enabled", "true");
        props.put("scan.reactive.types.enabled", "true");
        boolean nativeRuntime = RUNTIME_NATIVE.equals(runtime);
        props.put("serviceloading.jit.enabled", String.valueOf(!nativeRuntime));
        props.put("serviceloading.native.enabled", String.valueOf(nativeRuntime));
        props.put("cached.environment.enabled", String.valueOf(!nativeRuntime));
        props.put("deduce.environment.enabled", String.valueOf(!nativeRuntime));
        props.put("precompute.environment.properties.enabled", String.valueOf(!nativeRuntime));
        return props;
    }

    /**
     * {@code Properties.store} always prepends a {@code #<current date>} line and writes keys in
     * unspecified {@code Hashtable} order, so two identical AOT runs would produce two different
     * files. jk fixes timestamps everywhere else it writes an output; this is that contract
     * applied to a text file (JK-1666).
     */
    static String renderProperties(Properties props) {
        StringBuilder sb = new StringBuilder("# Effective Micronaut AOT configuration (jk)\n");
        List<String> keys = new ArrayList<>(props.stringPropertyNames());
        keys.sort(java.util.Comparator.naturalOrder());
        for (String key : keys) {
            sb.append(escape(key, true))
                    .append('=')
                    .append(escape(props.getProperty(key), false))
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * {@code java.util.Properties} escaping, so what we write round-trips through
     * {@link Properties#load}. Keys additionally escape the separators that would otherwise end
     * the key early.
     */
    private static String escape(String value, boolean isKey) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\f' -> sb.append("\\f");
                case '=', ':', '#', '!' -> sb.append('\\').append(c);
                // A leading space is significant in a value and always in a key.
                case ' ' -> sb.append(isKey || i == 0 ? "\\ " : " ");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * The user's AOT properties file, or {@code null} to use jk's defaults.
     *
     * <p>An <em>unset</em> {@code aot-config} falls back: {@code aot.properties} if it happens to
     * be there, else defaults. An <em>explicitly set</em> one does not — a typo'd path would
     * otherwise produce a green build that silently optimized with jk's defaults instead of the
     * configuration the user wrote, and nothing in the output would say so (JK-1662).
     */
    /**
     * The module-relative properties file this build reads: {@code aot-config} when set, else the
     * conventional {@code aot.properties}. One spelling for the declared input and the step body,
     * so the action key covers the file the body actually opens.
     */
    static String configSpec(PluginConfig cfg) {
        return cfg.stringOpt("aot-config")
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .orElse(DEFAULT_AOT_CONFIG);
    }

    static Path userConfigFile(Path moduleDir, String configured) throws IOException {
        String spec = configured == null ? "" : configured.trim();
        if (spec.isEmpty()) {
            Path conventional = moduleDir.resolve(DEFAULT_AOT_CONFIG);
            return Files.isRegularFile(conventional) ? conventional : null;
        }
        Path named = moduleDir.resolve(spec);
        if (!Files.isRegularFile(named)) {
            throw new IOException("[micronaut] aot-config = \"" + spec + "\" does not name a readable file ("
                    + named.toAbsolutePath().normalize()
                    + "). Fix the path, or remove the key to use jk's default optimizers.");
        }
        return named;
    }

    static List<Path> jarsIn(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .forEach(out::add);
        }
        // Files.walk order is directory-iteration order — it varies by filesystem and by the
        // order entries were created. This list becomes both --classpath and the forked JVM's
        // -cp, so an unsorted closure hands AOT a different classpath on two machines holding
        // identical jars, and a duplicate class resolves differently (JK-1665).
        out.sort(java.util.Comparator.comparing(Path::toString));
        return out;
    }

    static String joinCp(List<Path> paths) throws IOException {
        String sep = System.getProperty("path.separator", ":");
        StringBuilder sb = new StringBuilder();
        for (Path p : paths) {
            // A missing entry silently shortening the classpath is how you get an AOT run that
            // "succeeds" with half its optimizers unavailable.
            if (!Files.exists(p)) {
                throw new IOException("Micronaut AOT classpath entry does not exist: " + p.toAbsolutePath());
            }
            if (sb.length() > 0) sb.append(sep);
            sb.append(p.toAbsolutePath().normalize());
        }
        return sb.toString();
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path src : (Iterable<Path>) walk::iterator) {
                Path rel = from.relativize(src);
                Path dst = to.resolve(rel.toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst);
                }
            }
        }
    }

    private static String tail(String output) {
        if (output == null || output.isBlank()) return "(no output)";
        String[] lines = output.split("\n");
        int from = Math.max(0, lines.length - 40);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }
}
