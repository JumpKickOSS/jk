// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildContext;
import cc.jumpkick.plugin.build.BuildExtension;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.PackageContext;
import cc.jumpkick.plugin.build.PackageExtension;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.TaskExec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Quarkus build plugin: {@code quarkus-augment} step + {@code quarkus-fast-jar}
 * packager.
 *
 * <p>Augmentation forks {@link QuarkusAugmentMain} on a BOM-aligned bootstrap tool classpath
 * (one step-dep {@code quarkus-bootstrap}: core + maven-resolver under {@code quarkus-bootstrap-bom}).
 * The packager consumes the augment output ({@code quarkus-run.jar} fast-jar layout or uber runner); augment failure fails the build.
 */
public final class QuarkusPlugin implements Plugin, BuildExtension, PackageExtension {

    static final String AUGMENT_STEP = "quarkus-augment";
    private static final String BOOTSTRAP_EXTRA = "quarkus-bootstrap";
    static final String PLATFORM_PROPS_EXTRA = "quarkus-platform-properties";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-quarkus", "##JKQ:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void build(BuildContext ctx) {
        // native-sources is the directory Quarkus writes when asked for a native build without
        // running native-image: the runner jar, its lib/, and the argument list it computed.
        // Declared as an output so the engine can find it and the action cache covers it.
        ctx.named(AUGMENT_STEP)
                .inputs(In.classes(), In.runtimeEntries(), In.config())
                .outputs("quarkus-app", "native-sources")
                .run(QuarkusPlugin::runAugment);
    }

    @Override
    public void pack(PackageContext ctx) {
        ctx.inputs(In.classes(), In.runtimeEntries(), In.stepOutput(AUGMENT_STEP), In.config())
                .produce("quarkus-fast-jar", QuarkusPlugin::produceFastJar);
    }

    private static void runAugment(TaskExec exec) throws Exception {
        Path classes = exec.classesDir();
        if (!Files.isDirectory(classes)) {
            throw new IOException("no classes to augment at " + classes);
        }

        Path outRoot = exec.outputDir("quarkus-app");
        Path nativeSourcesOut = exec.outputDir("native-sources");
        Path listFile = exec.scratch().resolve("runtime-jars.tsv");
        List<String> lines = new ArrayList<>();
        for (PackageIo.RuntimeEntry e : exec.runtimeEntries()) {
            Path jar = e.jar();
            if (jar == null || !Files.isRegularFile(jar)) continue;
            // The coordinate rides on the entry. Deriving it from the path cannot work: jk serves
            // the runtime classpath out of the content-addressed store, so `jar` is a hash.
            // Workspace siblings carry no coordinate and the augment synthesizes one.
            String gav = e.gav();
            if (gav.isEmpty()) {
                gav = "unknown:unknown:0";
            }
            lines.add(gav + "\t" + jar.toAbsolutePath().normalize());
        }
        Files.write(listFile, lines, StandardCharsets.UTF_8);

        // Pure bootstrap: worker jar + one BOM-aligned tool closure (step-dep quarkus-bootstrap).
        // Engine resolves core + maven-resolver under quarkus-bootstrap-bom — no dual freestyle
        // trees, no hand-pinned smallrye modules.
        Path workerJar = pluginJar();
        List<Path> cp = new ArrayList<>();
        cp.add(workerJar);
        Path tools = exec.requireExtra(BOOTSTRAP_EXTRA);
        if (Files.isDirectory(tools)) {
            cp.addAll(jarsIn(tools));
        } else if (Files.isRegularFile(tools)) {
            cp.add(tools);
        } else {
            throw new IOException("step-dependency `" + BOOTSTRAP_EXTRA + "` missing at " + tools);
        }

        String baseName = exec.project().name();
        exec.label("quarkus augment (" + baseName + ")");

        // [quarkus] version is a major-line floor ("3"); the augment hands it to Maven as a real
        // version when it resolves the platform BOM and its properties artifact. Take the version
        // the lock actually chose, which is what quarkus-core resolved to.
        String quarkusVersion = resolvedQuarkusVersion(exec.runtimeEntries())
                .orElseGet(() -> exec.config().string("version"));
        String packageType =
                normalizePackageType(exec.config().stringOpt("package").orElse("fast-jar"));
        TaskExec.ToolRun.Result run = exec.java()
                .classpath(cp)
                .arg("-Djava.util.logging.manager=org.jboss.logmanager.LogManager")
                .arg("-Djk.quarkus.package.type=" + packageType)
                .arg("-Djk.quarkus.native.sources=" + exec.project().nativeDeclared())
                .arg("-Djk.quarkus.native.sources.out=" + nativeSourcesOut)
                .mainClass(QuarkusAugmentMain.class.getName())
                .args(augmentArgs(exec, classes, outRoot, baseName, listFile, quarkusVersion))
                .cwd(exec.moduleDir())
                .run();
        if (run.exit() != 0) {
            // Fail loudly: a cached "success" with no quarkus-app would silently ship a
            // non-production artifact on every later build.
            throw new IOException("quarkus-augment failed (exit " + run.exit() + "):\n" + tail(run.output()));
        }
    }

    /**
     * The augment's positional argument vector — the contract {@link QuarkusAugmentMain#main}
     * parses, in one place so both ends can be read together and pinned by a test.
     *
     * <p>Offline is positional rather than a {@code -D}: the augment is a grandchild JVM, and the
     * engine's per-job decision has to arrive as data it cannot be launched without. The platform
     * properties path is positional for the same reason — it is a step-dependency the engine
     * fetched through jk's repo stack, and the augment must never resolve the coordinate itself.
     */
    static List<String> augmentArgs(
            TaskExec exec, Path classes, Path outRoot, String baseName, Path listFile, String quarkusVersion) {
        return List.of(
                exec.moduleDir().toString(),
                classes.toString(),
                outRoot.toString(),
                baseName,
                exec.project().group(),
                exec.project().name(),
                exec.project().version(),
                listFile.toString(),
                quarkusVersion,
                exec.requireExtra(PLATFORM_PROPS_EXTRA).toString(),
                Boolean.toString(exec.offline()));
    }

    /** {@code fast-jar} (default) or {@code uber-jar}; unknown values fail early. */
    static String normalizePackageType(String raw) throws IOException {
        if (raw == null || raw.isBlank()) return "fast-jar";
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if ("fast-jar".equals(t) || "fastjar".equals(t) || "fast".equals(t)) return "fast-jar";
        if ("uber-jar".equals(t) || "uberjar".equals(t) || "uber".equals(t) || "fat-jar".equals(t)) {
            return "uber-jar";
        }
        throw new IOException("[quarkus] package must be \"fast-jar\" or \"uber-jar\" (got \"" + raw + "\")");
    }

    private static List<Path> jarsIn(Path dir) throws IOException {
        List<Path> jars = new ArrayList<>();
        try (var listing = Files.list(dir)) {
            listing.filter(f -> f.toString().endsWith(".jar")).sorted().forEach(jars::add);
        }
        return jars;
    }

    static void produceFastJar(PackageIo io) throws Exception {
        Path outJar = io.artifactPath();
        Files.createDirectories(outJar.getParent());

        Path augmentRoot = io.stepOutput(AUGMENT_STEP)
                .orElseThrow(() -> new IOException("quarkus-augment produced no output — rebuild with --redo"));
        // Uber-jar: single self-contained runner (no sibling lib/).
        Path uber = findUberJar(augmentRoot);
        if (uber != null) {
            io.label("package " + outJar.getFileName() + " (quarkus uber-jar)");
            Files.copy(uber, outJar, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        // Fast-jar: quarkus-run.jar + relative Class-Path into lib/.
        Path runJar = findQuarkusRunJar(augmentRoot);
        if (runJar == null) {
            throw new IOException("no quarkus-run.jar or *-runner.jar under " + augmentRoot
                    + " — augment output is incomplete; rebuild with --redo");
        }
        Path layoutRoot = runJar.getParent(); // directory containing quarkus-run.jar + lib/
        io.label("package " + outJar.getFileName() + " (quarkus-run.jar)");
        Path outDir = outJar.getParent();
        for (String child : List.of("lib", "app", "quarkus")) {
            Path src = layoutRoot.resolve(child);
            if (Files.isDirectory(src)) {
                Path dest = outDir.resolve(child);
                if (Files.exists(dest)) {
                    deleteTree(dest);
                }
                copyTree(src, dest);
                io.produced(dest);
            }
        }
        Files.copy(runJar, outJar, StandardCopyOption.REPLACE_EXISTING);
        // Canonical quarkus-app/ tree for docs / docker layering.
        Path appDir = outDir.resolve("quarkus-app");
        if (Files.exists(appDir)) {
            deleteTree(appDir);
        }
        copyTree(layoutRoot, appDir);
        // The run jar's Class-Path needs these siblings — declare them so a packaging
        // cache hit after `jk clean` restores a runnable layout, not a lone jar.
        io.produced(appDir);
    }

    private static Path findQuarkusRunJar(Path root) throws IOException {
        if (!Files.isDirectory(root)) return null;
        // Prefer a runner whose sibling lib/ exists (fast-jar Class-Path is relative).
        Path nested = root.resolve("quarkus-app").resolve("quarkus-run.jar");
        if (Files.isRegularFile(nested) && Files.isDirectory(nested.getParent().resolve("lib"))) {
            return nested;
        }
        Path direct = root.resolve("quarkus-run.jar");
        if (Files.isRegularFile(direct) && Files.isDirectory(root.resolve("lib"))) {
            return direct;
        }
        try (Stream<Path> walk = Files.walk(root, 5)) {
            // Cheapest rejection first: the name test is free, isRegularFile re-resolves the path for
            // a stat, and the sibling-directory probe is a second stat — so the name goes first and
            // the two stats only run for the handful of entries actually called quarkus-run.jar
            // (JK-1030).
            return walk.filter(p -> p.getFileName().toString().equals("quarkus-run.jar"))
                    .filter(Files::isRegularFile)
                    .filter(p -> Files.isDirectory(p.getParent().resolve("lib")))
                    .findFirst()
                    .orElse(Files.isRegularFile(direct) ? direct : null);
        }
    }

    /** Uber-jar runner written by augment ({@code quarkus-uber.jar} staging or {@code *-runner.jar}). */
    private static Path findUberJar(Path root) throws IOException {
        if (!Files.isDirectory(root)) return null;
        Path staged = root.resolve("quarkus-uber.jar");
        if (Files.isRegularFile(staged)) return staged;
        try (Stream<Path> walk = Files.walk(root, 5)) {
            // Free test first: the walk already paid for this entry, and isRegularFile re-resolves
            // the path for a fresh stat even for entries the name test discards (JK-1030).
            return walk.filter(p -> p.getFileName().toString().endsWith("-runner.jar"))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * The Quarkus version this build resolved to, read off {@code io.quarkus:quarkus-core} in the
     * runtime closure. Empty when the closure has no Quarkus core — the caller falls back to the
     * configured floor and the augment reports whatever Maven makes of it.
     */
    static Optional<String> resolvedQuarkusVersion(List<PackageIo.RuntimeEntry> entries) {
        for (PackageIo.RuntimeEntry e : entries) {
            if ("io.quarkus".equals(e.group()) && "quarkus-core".equals(e.artifact())) {
                return e.version().isEmpty() ? Optional.empty() : Optional.of(e.version());
            }
        }
        return Optional.empty();
    }

    private static Path pluginJar() throws IOException {
        try {
            URI uri = QuarkusPlugin.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI();
            Path p = Path.of(uri);
            if (!Files.isRegularFile(p)) {
                throw new IOException("plugin code source is not a jar: " + p);
            }
            return p;
        } catch (Exception e) {
            throw new IOException("cannot locate jk-quarkus worker jar", e);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        // `.jk-*` is the plugin-scratch convention; it must not ride into a staged layout.
        PathUtil.copyTree(
                from,
                to,
                dir -> dir.getFileName() != null && dir.getFileName().toString().startsWith(".jk-"));
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String tail(String output) {
        if (output == null || output.isBlank()) return "";
        String[] lines = output.split("\n");
        int from = Math.max(0, lines.length - 60);
        return String.join("\n", Arrays.copyOfRange(lines, from, lines.length));
    }
}
