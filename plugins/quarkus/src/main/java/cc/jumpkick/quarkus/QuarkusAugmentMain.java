// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import io.quarkus.bootstrap.app.AugmentResult;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.PlatformImportsImpl;
import io.quarkus.bootstrap.resolver.BootstrapAppModelResolver;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactDependency;
import io.quarkus.maven.dependency.Dependency;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Forked entry point for Quarkus production packaging.
 *
 * <p>Args: {@code projectRoot classesDir targetDir baseName group artifact version runtimeListFile
 * quarkusVersion}
 *
 * <p>Pure bootstrap — no {@code mvn} CLI. Builds an {@code ApplicationModel} via Quarkus's
 * embedded Maven resolver (BootstrapAppModelResolver), injects platform properties/descriptor,
 * then runs {@code createProductionApplication} to produce {@code quarkus-app/}.
 */
public final class QuarkusAugmentMain {

    public static void main(String[] args) throws Exception {
        if (args.length != 9) {
            System.err.println(
                    "usage: QuarkusAugmentMain projectRoot classesDir targetDir baseName group artifact version runtimeListFile quarkusVersion");
            System.exit(2);
        }
        Path appProjectRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path classesDir = Path.of(args[1]).toAbsolutePath().normalize();
        Path targetDir = Path.of(args[2]).toAbsolutePath().normalize();
        String baseName = args[3];
        String group = args[4];
        String artifact = args[5];
        String version = args[6];
        Path runtimeList = Path.of(args[7]).toAbsolutePath().normalize();
        String quarkusVersion = args[8];

        List<RuntimeCoord> runtime = parseRuntimeList(runtimeList);
        List<RuntimeCoord> extensions = discoverExtensions(runtime);
        System.err.println("jk-quarkus-augment: runtime=" + runtime.size() + " extensions=" + extensions.size()
                + " pure-bootstrap");

        Files.createDirectories(targetDir);
        Path scratch = Files.createDirectories(targetDir.resolve(".jk-quarkus-bootstrap"));
        Path localRepo = Files.createDirectories(scratch.resolve("m2"));
        Path appJar = scratch.resolve("app.jar");
        jarDir(classesDir, appJar);

        // Prefer jk CAS + user m2 as tails so already-fetched jars are reused.
        String jkCentral = Path.of(System.getProperty("user.home"), ".jk/cache/repos/central")
                .toString();
        String m2 = Path.of(System.getProperty("user.home"), ".m2/repository").toString();

        var cfg = BootstrapMavenContext.config()
                .setLocalRepository(localRepo.toString())
                .setLocalRepositoryTail(jkCentral, m2)
                .setWorkspaceDiscovery(false);
        MavenArtifactResolver maven = new MavenArtifactResolver(new BootstrapMavenContext(cfg));
        BootstrapAppModelResolver modelResolver = new BootstrapAppModelResolver(maven);

        ArtifactCoords appCoords = ArtifactCoords.jar(group, artifact, version);
        modelResolver.install(appCoords, appJar);
        // Point the app artifact at compiled classes for augmentation root content.
        modelResolver.relink(appCoords, classesDir);

        List<Dependency> direct = new ArrayList<>();
        Set<String> directKeys = new LinkedHashSet<>();
        for (RuntimeCoord e : extensions) {
            String key = e.group() + ":" + e.artifact();
            if (directKeys.add(key)) {
                direct.add(new ArtifactDependency(e.group(), e.artifact(), "", "jar", e.version(), "compile", false));
            }
        }
        // Workspace / path jars (jk path deps) have no Maven layout GAV — install them into the
        // bootstrap local repo and declare as direct deps so they land in quarkus-app/lib/main.
        int pathDeps = 0;
        for (RuntimeCoord r : runtime) {
            if (!isPathOrUnknown(r)) continue;
            RuntimeCoord fixed = synthesizeCoords(r);
            ArtifactCoords c = ArtifactCoords.jar(fixed.group(), fixed.artifact(), fixed.version());
            modelResolver.install(c, r.jar());
            String key = fixed.group() + ":" + fixed.artifact();
            if (directKeys.add(key)) {
                direct.add(new ArtifactDependency(
                        fixed.group(), fixed.artifact(), "", "jar", fixed.version(), "compile", false));
                pathDeps++;
            }
        }
        if (direct.isEmpty()) {
            // Fall back to all non-unknown runtime coords as direct deps.
            for (RuntimeCoord r : runtime) {
                if (r.group().startsWith("unknown")) continue;
                String key = r.group() + ":" + r.artifact();
                if (directKeys.add(key)) {
                    direct.add(
                            new ArtifactDependency(r.group(), r.artifact(), "", "jar", r.version(), "compile", false));
                }
            }
        }
        ArtifactCoords managing = ArtifactCoords.pom("io.quarkus.platform", "quarkus-bom", quarkusVersion);

        System.err.println("jk-quarkus-augment: resolving ApplicationModel (direct=" + direct.size() + " pathDeps="
                + pathDeps + ")…");
        // Bootstrap 3.38+: (app, directDeps, excludedArtifacts, managingProject, reloadableModules).
        var model = modelResolver.resolveManagedModel(
                appCoords, direct, Set.of(), managing, Set.of(appCoords.getKey()));
        System.err.println(
                "jk-quarkus-augment: model deps=" + model.getDependencies().size());

        // Platform properties + descriptor (required for config expansion + alignment checks).
        injectPlatform(model, quarkusVersion, jkCentral, m2, maven);

        String packageType = normalizePackageType(System.getProperty("jk.quarkus.package.type", "fast-jar"));
        Properties bsp = new Properties();
        bsp.setProperty("quarkus.package.jar.type", packageType);
        bsp.setProperty("quarkus.analytics.disabled", "true");

        Path augmentOut = Files.createDirectories(scratch.resolve("out"));
        QuarkusBootstrap bs = QuarkusBootstrap.builder()
                .setApplicationRoot(classesDir)
                .setProjectRoot(appProjectRoot)
                .setTargetDirectory(augmentOut)
                .setBaseName(baseName)
                .setOriginalBaseName(baseName)
                .setMode(QuarkusBootstrap.Mode.PROD)
                .setIsolateDeployment(true)
                .setLocalProjectDiscovery(false)
                .setExistingModel(model)
                .setBuildSystemProperties(bsp)
                .setRebuild(false)
                .build();

        System.err.println(
                "jk-quarkus-augment: bootstrap + createProductionApplication (package=" + packageType + ")…");
        Path producedJar = null;
        try (CuratedApplication curated = bs.bootstrap()) {
            AugmentResult result = curated.createAugmentor().createProductionApplication();
            if (result.getJar() != null) {
                producedJar = result.getJar().getPath();
            }
            System.err.println("jk-quarkus-augment: result jar=" + producedJar);
        }

        if ("uber-jar".equals(packageType)) {
            Path uber = findProducedUberJar(augmentOut, producedJar);
            if (uber == null || !Files.isRegularFile(uber)) {
                throw new IllegalStateException("uber-jar not produced under " + augmentOut);
            }
            Path dest = targetDir.resolve("quarkus-uber.jar");
            Files.copy(uber, dest, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("jk-quarkus-augment: " + dest);
            return;
        }

        Path quarkusApp = augmentOut.resolve("quarkus-app");
        Path runJar = quarkusApp.resolve("quarkus-run.jar");
        if (!Files.isRegularFile(runJar)) {
            try (var walk = Files.walk(augmentOut, 4)) {
                runJar = walk.filter(p -> p.getFileName().toString().equals("quarkus-run.jar"))
                        .filter(Files::isRegularFile)
                        .findFirst()
                        .orElse(null);
            }
        }
        if (runJar == null || !Files.isRegularFile(runJar)) {
            throw new IllegalStateException("quarkus-run.jar not produced under " + augmentOut);
        }

        Path destApp = targetDir.resolve("quarkus-app");
        if (Files.isDirectory(destApp)) {
            deleteTree(destApp);
        }
        // Promote the layout next to the runner (lib/app/quarkus siblings).
        Path layoutRoot = runJar.getParent();
        copyTree(layoutRoot, destApp);
        Files.copy(runJar, targetDir.resolve("quarkus-run.jar"), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("jk-quarkus-augment: " + targetDir.resolve("quarkus-run.jar"));
    }

    private static String normalizePackageType(String raw) {
        if (raw == null || raw.isBlank()) return "fast-jar";
        String t = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("uber-jar".equals(t) || "uberjar".equals(t) || "uber".equals(t) || "fat-jar".equals(t)) {
            return "uber-jar";
        }
        return "fast-jar";
    }

    private static Path findProducedUberJar(Path augmentOut, Path producedJar) throws IOException {
        if (producedJar != null && Files.isRegularFile(producedJar)) {
            return producedJar;
        }
        try (var walk = Files.walk(augmentOut, 5)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith("-runner.jar") || n.endsWith("-runner");
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    private static void injectPlatform(
            io.quarkus.bootstrap.model.ApplicationModel model,
            String quarkusVersion,
            String jkCentral,
            String m2,
            MavenArtifactResolver maven)
            throws Exception {
        if (!(model.getPlatforms() instanceof PlatformImportsImpl platforms)) {
            System.err.println("jk-quarkus-augment: warning: cannot inject platform props (platforms type "
                    + (model.getPlatforms() == null
                            ? "null"
                            : model.getPlatforms().getClass().getName()) + ")");
            return;
        }
        Path propsPath = Path.of(
                jkCentral,
                "io/quarkus/platform/quarkus-bom-quarkus-platform-properties",
                quarkusVersion,
                "quarkus-bom-quarkus-platform-properties-" + quarkusVersion + ".properties");
        if (!Files.isRegularFile(propsPath)) {
            propsPath = Path.of(
                    m2,
                    "io/quarkus/platform/quarkus-bom-quarkus-platform-properties",
                    quarkusVersion,
                    "quarkus-bom-quarkus-platform-properties-" + quarkusVersion + ".properties");
        }
        if (!Files.isRegularFile(propsPath)) {
            var art = new org.eclipse.aether.artifact.DefaultArtifact(
                    "io.quarkus.platform", "quarkus-bom-quarkus-platform-properties", "", "properties", quarkusVersion);
            propsPath = resolvedArtifactPath(maven.resolve(art).getArtifact());
        }
        platforms.addPlatformProperties(
                "io.quarkus.platform",
                "quarkus-bom-quarkus-platform-properties",
                "",
                "properties",
                quarkusVersion,
                propsPath);
        // Marks the BOM import as having a platform descriptor (alignment check).
        platforms.addPlatformDescriptor(
                "io.quarkus.platform", "quarkus-bom-quarkus-platform-descriptor", "", "json", quarkusVersion);
        System.err.println("jk-quarkus-augment: platform props="
                + model.getPlatformProperties().size() + " boms=" + platforms.getImportedPlatformBoms());
    }

    private record RuntimeCoord(String group, String artifact, String version, Path jar) {}

    /**
     * Path of a resolved Aether artifact. Prefer {@code getPath} (maven-resolver 1.9.20+ / 2.x);
     * fall back to {@code getFile} for the older resolver pinned by quarkus-bootstrap. Looked up
     * reflectively so compile against either surface stays free of deprecation noise and missing
     * symbols.
     */
    static Path resolvedArtifactPath(org.eclipse.aether.artifact.Artifact art) {
        if (art == null) throw new IllegalStateException("resolved artifact is null");
        try {
            Object path = art.getClass().getMethod("getPath").invoke(art);
            if (path instanceof Path p) return p;
        } catch (ReflectiveOperationException ignored) {
            // Older Artifact interface — only getFile.
        }
        try {
            Object file = art.getClass().getMethod("getFile").invoke(art);
            if (file instanceof java.io.File f) return f.toPath();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot resolve path for " + art, e);
        }
        throw new IllegalStateException("resolved artifact has no path: " + art);
    }

    /** Path/workspace jars written as {@code unknown:unknown:0} by the packager, or non-Maven paths. */
    private static boolean isPathOrUnknown(RuntimeCoord r) {
        return r.group().startsWith("unknown")
                || "0".equals(r.version())
                        && r.jar() != null
                        && !r.jar().toString().replace('\\', '/').contains("/repos/");
    }

    /**
     * Derive installable GAV for a workspace jar: prefer {@code name-version.jar} filename, else a
     * stable hash of the path.
     */
    private static RuntimeCoord synthesizeCoords(RuntimeCoord r) {
        if (!r.group().startsWith("unknown") && !"0".equals(r.version())) {
            return r;
        }
        String file = r.jar().getFileName().toString();
        String base = file.endsWith(".jar") ? file.substring(0, file.length() - 4) : file;
        // domain-0.1.0 → artifact=domain version=0.1.0
        String artifact = base;
        String version = "0.1.0";
        int dash = base.lastIndexOf('-');
        if (dash > 0 && dash < base.length() - 1) {
            String maybeVer = base.substring(dash + 1);
            if (maybeVer.matches("[0-9].*")) {
                artifact = base.substring(0, dash);
                version = maybeVer;
            }
        }
        String group = "jk.workspace";
        return new RuntimeCoord(group, artifact, version, r.jar());
    }

    private static List<RuntimeCoord> parseRuntimeList(Path file) throws Exception {
        List<RuntimeCoord> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] parts = line.split("\t", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("bad runtime list line: " + line);
            }
            String[] gav = parts[0].split(":", 3);
            if (gav.length != 3) {
                throw new IllegalArgumentException("bad GAV: " + parts[0]);
            }
            out.add(new RuntimeCoord(gav[0], gav[1], gav[2], Path.of(parts[1])));
        }
        return out;
    }

    private static List<RuntimeCoord> discoverExtensions(List<RuntimeCoord> runtime) {
        List<RuntimeCoord> extensions = new ArrayList<>();
        for (RuntimeCoord d : runtime) {
            if (isQuarkusExtension(d.jar())) {
                extensions.add(d);
            }
        }
        if (extensions.isEmpty()) {
            for (RuntimeCoord d : runtime) {
                if (d.group().startsWith("io.quarkus")
                        && !d.artifact().contains("bootstrap")
                        && !d.artifact().endsWith("-spi")
                        && !d.artifact().endsWith("-deployment")) {
                    extensions.add(d);
                }
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        List<RuntimeCoord> ordered = new ArrayList<>();
        for (String want : List.of("quarkus-rest", "quarkus-arc", "quarkus-core")) {
            for (RuntimeCoord d : extensions) {
                if (d.artifact().equals(want) && seen.add(d.group() + ":" + d.artifact())) {
                    ordered.add(d);
                }
            }
        }
        for (RuntimeCoord d : extensions) {
            if (seen.add(d.group() + ":" + d.artifact())) {
                ordered.add(d);
            }
        }
        // Cap direct deps — resolver still walks the full managed graph.
        return ordered.size() > 30 ? ordered.subList(0, 30) : ordered;
    }

    private static boolean isQuarkusExtension(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) return false;
        try (JarFile jf = new JarFile(jar.toFile())) {
            return jf.getEntry("META-INF/quarkus-extension.properties") != null
                    || jf.getEntry("META-INF/quarkus-extension.yaml") != null
                    || jf.getEntry("META-INF/quarkus-extension.yml") != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static void jarDir(Path dir, Path jar) throws IOException {
        Manifest man = new Manifest();
        man.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream fos = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(fos, man)) {
            if (!Files.isDirectory(dir)) return;
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = dir.relativize(file).toString().replace('\\', '/');
                    // Pin entry times (setTimeLocal: TZ-safe) so repeated augments produce
                    // byte-identical jars — raw-jar fingerprints key downstream action caches.
                    JarEntry entry = new JarEntry(name);
                    entry.setTimeLocal(java.time.LocalDateTime.of(1980, 2, 1, 0, 0));
                    jos.putNextEntry(entry);
                    Files.copy(file, jos);
                    jos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
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

    private QuarkusAugmentMain() {}
}
