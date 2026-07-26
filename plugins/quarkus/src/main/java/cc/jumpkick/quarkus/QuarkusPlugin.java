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
import java.io.OutputStream;
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
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Quarkus build plugin (JK-1159/1160/1202): {@code quarkus-augment} step + {@code quarkus-fast-jar}
 * packager.
 *
 * <p>Augmentation forks {@link QuarkusAugmentMain} on an isolated bootstrap classpath (step-dep
 * {@code quarkus-bootstrap}) so deployment resolution does not mix with the app's test-time Maven
 * embedder jars. The packager prefers the augmentor's {@code quarkus-run.jar}; if augmentation is
 * unavailable it falls back to the MVP fat-jar so lock/build still produces an artifact.
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

        // Pure bootstrap: worker jar + step-dep closures (bootstrap-core, maven-resolver, smallrye-common).
        // smallrye-common must be 2.13.x matching Quarkus 3.28 — older jars on the parent CL cause
        // NoSuchMethodError in Assert during config mapping (parent-first for io.smallrye.common).
        Path workerJar = pluginJar();
        List<Path> cp = new ArrayList<>();
        cp.add(workerJar);
        addExtraJars(cp, exec, BOOTSTRAP_EXTRA, true);
        addExtraJars(cp, exec, "quarkus-bootstrap-maven", true);
        addExtraJars(cp, exec, "smallrye-common", false);
        // Also accept per-module smallrye-common-* extras if declared individually.
        for (String name : List.of(
                "smallrye-common-constraint",
                "smallrye-common-cpu",
                "smallrye-common-expression",
                "smallrye-common-function",
                "smallrye-common-io",
                "smallrye-common-net",
                "smallrye-common-os",
                "smallrye-common-ref")) {
            exec.extra(name).ifPresent(p -> {
                if (Files.isRegularFile(p)) cp.add(p);
                else if (Files.isDirectory(p)) {
                    try {
                        cp.addAll(jarsIn(p));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }
        dedupeClasspathPreferNewestSmallrye(cp);

        String baseName = exec.project().name();
        exec.label("quarkus augment (" + baseName + ")");

        String quarkusVersion = exec.config().string("version");
        StepExec.ToolRun.Result run = exec.java()
                .classpath(cp)
                .arg("-Djava.util.logging.manager=org.jboss.logmanager.LogManager")
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
            // Keep packaging usable while deployment/config model is still being hardened (JK-1160).
            // The packager falls back to the MVP fat-jar when quarkus-run.jar is absent.
            exec.label("quarkus-augment failed — packager will use fat-jar fallback");
            System.err.println("jk-quarkus: pure bootstrap failed (exit " + run.exit() + "); fat-jar fallback:\n"
                    + tail(run.output()));
            Files.writeString(
                    outRoot.resolve(".jk-augment-failed"),
                    tail(run.output()),
                    StandardCharsets.UTF_8);
        }
    }

    private static void addExtraJars(List<Path> cp, StepExec exec, String name, boolean skipOldSmallrye)
            throws IOException {
        Path extra = exec.extra(name).orElse(null);
        if (extra == null) return;
        if (Files.isRegularFile(extra)) {
            if (!(skipOldSmallrye && isSmallryeCommon(extra))) cp.add(extra);
            return;
        }
        if (Files.isDirectory(extra)) {
            for (Path j : jarsIn(extra)) {
                if (skipOldSmallrye && isSmallryeCommon(j)) continue;
                cp.add(j);
            }
        }
    }

    private static boolean isSmallryeCommon(Path jar) {
        String n = jar.getFileName().toString();
        return n.startsWith("smallrye-common-") || n.contains("smallrye-common-");
    }

    /**
     * When multiple smallrye-common versions land on the CP, keep the highest version per module
     * name so parent-first loading does not bind an older Assert (NoSuchMethodError).
     */
    private static void dedupeClasspathPreferNewestSmallrye(List<Path> cp) {
        java.util.LinkedHashMap<String, Path> byKey = new java.util.LinkedHashMap<>();
        List<Path> others = new ArrayList<>();
        for (Path p : cp) {
            String name = p.getFileName().toString();
            if (name.startsWith("smallrye-common-") && name.endsWith(".jar")) {
                // smallrye-common-<module>-<version>.jar
                int lastDash = name.lastIndexOf('-');
                int prevDash = name.lastIndexOf('-', lastDash - 1);
                // module id = everything before last version-ish segment is ambiguous; use full
                // prefix before version: strip trailing -<ver>.jar by finding first digit after common-
                String key = name.replaceAll("-\\d+\\.\\d+.*\\.jar$", "");
                Path existing = byKey.get(key);
                if (existing == null || name.compareTo(existing.getFileName().toString()) > 0) {
                    byKey.put(key, p);
                }
            } else {
                others.add(p);
            }
        }
        cp.clear();
        // smallrye-common first so parent CL loads the aligned set.
        cp.addAll(byKey.values());
        cp.addAll(others);
    }

    private static List<Path> jarsIn(Path dir) throws IOException {
        List<Path> jars = new ArrayList<>();
        try (var listing = Files.list(dir)) {
            listing.filter(f -> f.toString().endsWith(".jar")).sorted().forEach(jars::add);
        }
        return jars;
    }

    private static void produceFastJar(PackageIo io) throws Exception {
        Path outJar = io.artifactPath();
        Files.createDirectories(outJar.getParent());

        // Prefer real augmentor output: quarkus-app layout (runner + lib/ + app/).
        Path augmentRoot = io.stepOutput(AUGMENT_STEP).orElse(null);
        if (augmentRoot != null) {
            Path runJar = findQuarkusRunJar(augmentRoot);
            if (runJar != null) {
                Path layoutRoot = runJar.getParent(); // directory containing quarkus-run.jar + lib/
                io.label("package " + outJar.getFileName() + " (quarkus-run.jar)");
                // Fast-jar Class-Path is relative (lib/boot/…): place lib/app next to the main jar.
                Path outDir = outJar.getParent();
                for (String child : List.of("lib", "app", "quarkus")) {
                    Path src = layoutRoot.resolve(child);
                    if (Files.isDirectory(src)) {
                        Path dest = outDir.resolve(child);
                        if (Files.exists(dest)) {
                            deleteTree(dest);
                        }
                        copyTree(src, dest);
                    }
                }
                Files.copy(runJar, outJar, StandardCopyOption.REPLACE_EXISTING);
                // Also materialize the canonical quarkus-app/ tree for docs / docker layering.
                Path appDir = outDir.resolve("quarkus-app");
                if (Files.exists(appDir)) {
                    deleteTree(appDir);
                }
                copyTree(layoutRoot, appDir);
                return;
            }
        }

        // Fallback: MVP fat-jar (dev / when augment skipped). Not sufficient for REST production.
        String main = io.project().mainClass();
        if (main == null || main.isBlank()) {
            throw new IOException(
                    "quarkus packaging needs [application] main (e.g. Application with Quarkus.run)");
        }
        io.label("package " + outJar.getFileName() + " (quarkus MVP fat-jar fallback)");
        fatJarFallback(io, outJar, main);
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

    private static void fatJarFallback(PackageIo io, Path outJar, String main) throws Exception {
        Manifest man = new Manifest();
        Attributes attrs = man.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.put(Attributes.Name.MAIN_CLASS, main);
        attrs.putValue("Created-By", "jk-quarkus");
        attrs.putValue("Implementation-Title", io.project().name());
        attrs.putValue("Implementation-Version", io.project().version());

        java.util.Set<String> seen = new java.util.HashSet<>();
        try (OutputStream fos = Files.newOutputStream(outJar);
                JarOutputStream jos = new JarOutputStream(fos, man)) {
            Path classes = io.classesDir();
            if (Files.isDirectory(classes)) {
                addTree(jos, classes, seen);
            }
            for (PackageIo.RuntimeEntry entry : io.runtimeEntries()) {
                Path jar = entry.jar();
                if (jar == null || !Files.isRegularFile(jar)) continue;
                mergeJar(jos, jar, seen);
            }
        }
    }

    private static List<Path> jarsOn(Path root) throws IOException {
        if (Files.isRegularFile(root) && root.getFileName().toString().endsWith(".jar")) {
            return List.of(root);
        }
        if (!Files.isDirectory(root)) return List.of();
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .forEach(out::add);
        }
        return out;
    }

    private static List<Path> dedupeJars(List<Path> jars) {
        java.util.LinkedHashMap<String, Path> byName = new java.util.LinkedHashMap<>();
        for (Path j : jars) {
            byName.putIfAbsent(j.getFileName().toString(), j);
        }
        return new ArrayList<>(byName.values());
    }

    /** Parse {@code …/group/path/artifact/version/artifact-version.jar} → g:a:v. */
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
            URI uri = QuarkusPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI();
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

    private static void addTree(JarOutputStream jos, Path root, java.util.Set<String> seen) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = root.relativize(file).toString().replace('\\', '/');
                if (!seen.add(name)) return FileVisitResult.CONTINUE;
                jos.putNextEntry(new JarEntry(name));
                Files.copy(file, jos);
                jos.closeEntry();
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void mergeJar(JarOutputStream jos, Path jar, java.util.Set<String> seen) throws IOException {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name.startsWith("META-INF/MANIFEST.MF")) continue;
                if (name.startsWith("META-INF/INDEX.LIST")) continue;
                String upper = name.toUpperCase(Locale.ROOT);
                if (name.startsWith("META-INF/")
                        && (upper.endsWith(".SF")
                                || upper.endsWith(".RSA")
                                || upper.endsWith(".DSA")
                                || upper.endsWith(".EC"))) {
                    continue;
                }
                if (!seen.add(name)) continue;
                jos.putNextEntry(new JarEntry(name));
                try (var in = zf.getInputStream(e)) {
                    in.transferTo(jos);
                }
                jos.closeEntry();
            }
        }
    }

    private static String tail(String output) {
        if (output == null || output.isBlank()) return "";
        String[] lines = output.split("\n");
        int from = Math.max(0, lines.length - 60);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }
}
