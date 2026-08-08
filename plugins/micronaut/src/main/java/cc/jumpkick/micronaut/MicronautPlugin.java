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
import java.io.OutputStream;
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
            ctx.named(AOT_STEP)
                    .inputs(In.classes(), In.runtimeClasspath(), In.config())
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

        String runtime =
                exec.config().stringOpt("aot-runtime").orElse("jit").trim().toLowerCase(Locale.ROOT);
        if (!runtime.equals("jit") && !runtime.equals("native")) {
            throw new IOException("[micronaut] aot-runtime must be \"jit\" or \"native\" (got `" + runtime + "`)");
        }
        if (runtime.equals("native") && !exec.project().nativeDeclared()) {
            exec.label("note: aot-runtime=native without [native] — still generating native-oriented AOT");
        }
        if (runtime.equals("jit") && exec.project().nativeDeclared()) {
            exec.label("note: [native] present but aot-runtime=jit — prefer aot-runtime=native for native-image");
        }

        String pkg = exec.config()
                .stringOpt("aot-package")
                .filter(s -> !s.isBlank())
                .orElse(exec.project().group() + ".aot.generated");

        Path generated = exec.outputDir("generated");
        Path configFile = writeEffectiveConfig(exec, generated);

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
     * Effective aot.properties: user file when present ({@code aot-config} or {@code aot.properties}),
     * else a minimal default enabling common optimizers.
     */
    private static Path writeEffectiveConfig(TaskExec exec, Path generated) throws IOException {
        Properties props = new Properties();
        Path user = null;
        String configured = exec.config().stringOpt("aot-config").orElse("").trim();
        if (!configured.isEmpty()) {
            user = exec.moduleDir().resolve(configured);
        } else {
            Path def = exec.moduleDir().resolve("aot.properties");
            if (Files.isRegularFile(def)) user = def;
        }
        if (user != null && Files.isRegularFile(user)) {
            try (var in = Files.newInputStream(user)) {
                props.load(in);
            }
        } else {
            // Sensible defaults when the user has not authored a config yet.
            props.setProperty("cached.environment.enabled", "true");
            props.setProperty("logback.xml.to.java.enabled", "true");
            props.setProperty("yaml.to.java.config.enabled", "true");
            props.setProperty("scan.reactive.types.enabled", "true");
            props.setProperty("serviceloading.jit.enabled", "true");
            props.setProperty("precompute.environment.properties.enabled", "true");
            props.setProperty("deduce.environment.enabled", "true");
        }
        Path effective = generated.resolve("effective-aot.properties");
        Files.createDirectories(effective.getParent());
        try (OutputStream out = Files.newOutputStream(effective)) {
            props.store(out, "Effective Micronaut AOT configuration (jk)");
        }
        return effective;
    }

    private static List<Path> jarsIn(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".jar")).forEach(out::add);
        }
        return out;
    }

    private static String joinCp(List<Path> paths) {
        String sep = System.getProperty("path.separator", ":");
        StringBuilder sb = new StringBuilder();
        for (Path p : paths) {
            if (!Files.exists(p)) continue;
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
