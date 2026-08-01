// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildContext;
import cc.jumpkick.plugin.build.BuildExtension;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.PackageContext;
import cc.jumpkick.plugin.build.PackageExtension;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.plugin.build.StepExec;
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
import java.util.List;
import java.util.Locale;
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
        ctx.named(AUGMENT_STEP)
                .after(Phase.COMPILE)
                .before(Phase.PACKAGE)
                .inputs(In.classes(), In.runtimeEntries(), In.config())
                .outputs("quarkus-app")
                .run(QuarkusPlugin::runAugment);
    }

    @Override
    public void pack(PackageContext ctx) {
        ctx.inputs(In.classes(), In.runtimeEntries(), In.stepOutput(AUGMENT_STEP), In.config())
                .produce("quarkus-fast-jar", QuarkusPlugin::produceFastJar);
    }

    private static void runAugment(StepExec exec) throws Exception {
        Path classes = exec.classesDir();
        if (!Files.isDirectory(classes)) {
            throw new IOException("no classes to augment at " + classes);
        }

        Path outRoot = exec.outputDir("quarkus-app");
        Path listFile = exec.scratch().resolve("runtime-jars.tsv");
        List<String> lines = new ArrayList<>();
        for (PackageIo.RuntimeEntry e : exec.runtimeEntries()) {
            Path jar = e.jar();
            if (jar == null || !Files.isRegularFile(jar)) continue;
            String gav = gavFromPath(jar);
            if (gav == null) {
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

        String quarkusVersion = exec.config().string("version");
        String packageType =
                normalizePackageType(exec.config().stringOpt("package").orElse("fast-jar"));
        StepExec.ToolRun.Result run = exec.java()
                .classpath(cp)
                .arg("-Djava.util.logging.manager=org.jboss.logmanager.LogManager")
                .arg("-Djk.quarkus.package.type=" + packageType)
                .mainClass(QuarkusAugmentMain.class.getName())
                .arg(exec.moduleDir().toString())
                .arg(classes.toString())
                .arg(outRoot.toString())
                .arg(baseName)
                .arg(exec.project().group())
                .arg(exec.project().name())
                .arg(exec.project().version())
                .arg(listFile.toString())
                .arg(quarkusVersion)
                .cwd(exec.moduleDir())
                .run();
        if (run.exit() != 0) {
            // Fail loudly: a cached "success" with no quarkus-app would silently ship a
            // non-production artifact on every later build.
            throw new IOException("quarkus-augment failed (exit " + run.exit() + "):\n" + tail(run.output()));
        }
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
                .orElseThrow(() -> new IOException("quarkus-augment produced no output — rebuild with --rebuild"));
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
                    + " — augment output is incomplete; rebuild with --rebuild");
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
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("-runner.jar"))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Parse Maven-layout path {@code …/repos/…/group/path/artifact/version/artifact-version.jar}
     * → {@code g:a:v}. Workspace jars (no {@code /repos/}) return {@code null} so the caller can
     * fall back to {@code unknown:unknown:0}; the augment step re-synthesizes installable coords.
     */
    static String gavFromPath(Path jar) {
        try {
            Path verDir = jar.getParent();
            Path artDir = verDir.getParent();
            if (verDir == null || artDir == null || artDir.getParent() == null) return null;
            String version = verDir.getFileName().toString();
            String artifact = artDir.getFileName().toString();
            String sp = artDir.getParent().toString().replace('\\', '/');
            int idx = sp.indexOf("/repos/");
            if (idx < 0) return null;
            // /repos/<name>/group/path
            String after = sp.substring(idx + "/repos/".length());
            int slash = after.indexOf('/');
            if (slash < 0) return null;
            String gpath = after.substring(slash + 1);
            String group = gpath.replace('/', '.');
            return group + ":" + artifact + ":" + version;
        } catch (Exception e) {
            return null;
        }
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
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                // Skip nested bootstrap/maven scratch dirs if present under the layout root.
                String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                if (name.startsWith(".jk-")) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = to.resolve(from.relativize(file).toString());
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
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
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }
}
