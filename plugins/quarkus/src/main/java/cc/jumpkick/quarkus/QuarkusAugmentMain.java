// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.command.Exit;
import io.quarkus.bootstrap.app.AugmentResult;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.PlatformImportsImpl;
import io.quarkus.bootstrap.resolver.BootstrapAppModelResolver;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactDependency;
import io.quarkus.maven.dependency.Dependency;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Forked entry point for Quarkus production packaging.
 *
 * <p>Args: {@code projectRoot classesDir targetDir baseName group artifact version runtimeListFile
 * quarkusVersion platformPropsFile offline}
 *
 * <p>Pure bootstrap — no {@code mvn} CLI. Builds an {@code ApplicationModel} via Quarkus's
 * embedded Maven resolver (BootstrapAppModelResolver), injects platform properties/descriptor,
 * then runs {@code createProductionApplication} to produce {@code quarkus-app/}.
 *
 * <p>The embedded resolver's job stops at the <em>deployment</em> closure, which is build-time only.
 * What ships is jk's: the runtime list is the full locked closure, it is declared as the model's
 * direct dependencies, and {@link LockedAppModel} pins the resolved model's runtime classpath back
 * onto it before augmentation runs. See {@link LockedClosure}.
 */
public final class QuarkusAugmentMain {

    public static void main(String[] args) throws Exception {

        if (args.length != 11) {
            System.err.println(
                    "usage: QuarkusAugmentMain projectRoot classesDir targetDir baseName group artifact version"
                            + " runtimeListFile quarkusVersion platformPropsFile offline");
            System.exit(Exit.USAGE);
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
        // A step-dependency the engine fetched through jk's repo stack — the augment never
        // resolves the platform-properties coordinate itself.
        Path platformProps = Path.of(args[9]).toAbsolutePath().normalize();
        // Offline is a per-invocation decision the engine owns. It arrives as an argument, from
        // TaskExec.offline() -> the plugin spec -> the engine's session. Reading JK_OFFLINE or a
        // system property here would read the *engine daemon's* startup environment instead, so
        // one `JK_OFFLINE=1 jk build` would silently pin every later build in that session.
        boolean offline = EnvValues.parseBool(args[10]).orElse(false);

        LockedClosure locked = LockedClosure.parse(runtimeList);
        System.err.println("jk-quarkus-augment: locked runtime closure="
                + locked.artifacts().size() + (offline ? " offline" : "") + " pure-bootstrap");

        Files.createDirectories(targetDir);
        Path scratch = Files.createDirectories(targetDir.resolve(".jk-quarkus-bootstrap"));
        Path localRepo = Files.createDirectories(scratch.resolve("m2"));
        Path appJar = scratch.resolve("app.jar");
        AppJar.write(classesDir, appJar);

        // Reuse already-fetched jars: jk's repo mirrors are derived from the locked jar paths
        // the engine handed us — they ARE store paths, and rebuilding product dirs from
        // user.home guesses wrong the moment JK_STORE_DIR (or the platform default) differs.
        // ~/.m2 honors maven.repo.local for the same reason.
        List<String> tails = new ArrayList<>();
        for (Path reposRoot : locked.mirrorRepoRoots()) {
            tails.add(reposRoot.toString());
        }
        tails.add(System.getProperty(
                "maven.repo.local",
                Path.of(System.getProperty("user.home"), ".m2", "repository").toString()));

        var cfg = BootstrapMavenContext.config()
                .setLocalRepository(localRepo.toString())
                .setLocalRepositoryTail(tails.toArray(String[]::new))
                .setWorkspaceDiscovery(false);
        if (offline) {
            // Only ever forced ON — left unset, the user's Maven settings stay in charge. Offline
            // resolution serves the warm store and fails loudly on a miss; it never quietly
            // reaches Central behind an offline build's back.
            cfg.setOffline(true);
        }
        MavenArtifactResolver maven = new MavenArtifactResolver(new BootstrapMavenContext(cfg));
        BootstrapAppModelResolver modelResolver = new BootstrapAppModelResolver(maven);

        ArtifactCoords appCoords = ArtifactCoords.jar(group, artifact, version);
        modelResolver.install(appCoords, appJar);
        // Point the app artifact at compiled classes for augmentation root content.
        modelResolver.relink(appCoords, classesDir);

        // jk already solved the graph, so the augment declares the WHOLE locked closure as direct
        // dependencies instead of the handful of jars that looked like extensions. At depth 1 every
        // locked coordinate is the nearest one, so neither the platform BOM's managed versions nor
        // a deeper transitive can displace it. Workspace / path jars have no Maven layout GAV —
        // install them into the bootstrap local repo so the same declaration resolves.
        List<Dependency> direct = new ArrayList<>();
        int workspaceDeps = 0;
        for (LockedClosure.Artifact a : locked.artifacts()) {
            if (a.workspace()) {
                modelResolver.install(ArtifactCoords.jar(a.group(), a.artifact(), a.version()), a.jar());
                workspaceDeps++;
            }
            direct.add(new ArtifactDependency(a.group(), a.artifact(), "", "jar", a.version(), "compile", false));
        }
        ArtifactCoords managing = ArtifactCoords.pom("io.quarkus.platform", "quarkus-bom", quarkusVersion);

        System.err.println("jk-quarkus-augment: resolving ApplicationModel (direct=" + direct.size() + " workspaceDeps="
                + workspaceDeps + ")…");
        // Bootstrap 3.38+: (app, directDeps, excludedArtifacts, managingProject, reloadableModules).
        // Aether owns the deployment closure (build-time only); the lock owns what ships.
        var model = LockedAppModel.enforce(
                modelResolver.resolveManagedModel(appCoords, direct, Set.of(), managing, Set.of(appCoords.getKey())),
                locked);
        System.err.println("jk-quarkus-augment: runtime deps="
                + model.getRuntimeDependencies().size() + " deployment deps="
                + model.getDependencies().size());

        // Platform properties + descriptor (required for config expansion + alignment checks).
        injectPlatform(model, quarkusVersion, platformProps, offline);

        String packageType = normalizePackageType(System.getProperty("jk.quarkus.package.type", "fast-jar"));
        Properties bsp = new Properties();
        bsp.setProperty("quarkus.package.jar.type", packageType);
        bsp.setProperty("quarkus.analytics.disabled", "true");
        // Native: ask Quarkus to augment for the closed world and write the source jar plus the
        // native-image argument list it computed, without invoking native-image. jk owns that
        // invocation — its own GraalVM toolchain, progress and action cache — and Quarkus owns
        // knowing what the arguments are.
        boolean nativeSources = Boolean.getBoolean("jk.quarkus.native.sources");
        String nativeSourcesOut = System.getProperty("jk.quarkus.native.sources.out", "");
        if (nativeSources) {
            bsp.setProperty("quarkus.native.enabled", "true");
            bsp.setProperty("quarkus.native.sources-only", "true");
        }

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

        if (nativeSources && !nativeSourcesOut.isBlank()) {
            publishNativeSources(augmentOut, Path.of(nativeSourcesOut));
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
        String t = raw.trim().toLowerCase(Locale.ROOT);
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
            ApplicationModel model, String quarkusVersion, Path platformProps, boolean offline) throws Exception {
        if (!(model.getPlatforms() instanceof PlatformImportsImpl platforms)) {
            System.err.println("jk-quarkus-augment: warning: cannot inject platform props (platforms type "
                    + (model.getPlatforms() == null
                            ? "null"
                            : model.getPlatforms().getClass().getName()) + ")");
            return;
        }
        injectPlatformProperties(platforms, quarkusVersion, platformProps, offline);
        System.err.println("jk-quarkus-augment: platform props="
                + model.getPlatformProperties().size() + " boms=" + platforms.getImportedPlatformBoms());
    }

    /**
     * Register the engine-supplied platform properties file and the descriptor marker on the
     * model's platform imports. The file is a step-dependency the engine fetched through jk's own
     * repo stack; a miss here is a store defect, never a reason to resolve — this JVM must not
     * reach the network through the user's Maven environment.
     */
    static void injectPlatformProperties(
            PlatformImportsImpl platforms, String quarkusVersion, Path platformProps, boolean offline)
            throws Exception {
        if (!Files.isRegularFile(platformProps)) {
            String coordinate =
                    "io.quarkus.platform:quarkus-bom-quarkus-platform-properties:" + quarkusVersion + "!properties";
            if (offline) {
                throw new IOException(Errors.offlineRefusal(coordinate));
            }
            throw new IOException(coordinate + " is not at the engine-supplied path " + platformProps
                    + " — run `jk lock` to refresh the store, then rebuild");
        }
        platforms.addPlatformProperties(
                "io.quarkus.platform",
                "quarkus-bom-quarkus-platform-properties",
                "",
                "properties",
                quarkusVersion,
                platformProps);
        // Marks the BOM import as having a platform descriptor (alignment check).
        platforms.addPlatformDescriptor(
                "io.quarkus.platform", "quarkus-bom-quarkus-platform-descriptor", "", "json", quarkusVersion);
    }

    /**
     * Move Quarkus's {@code native-sources/} — runner jar, {@code lib/}, and the
     * {@code native-image.args} it computed — to the step's declared output, where the engine's
     * native-image step reads it.
     */
    private static void publishNativeSources(Path augmentOut, Path dest) throws IOException {
        Path found = null;
        try (var walk = Files.walk(augmentOut, 6)) {
            found = walk.filter(Files::isDirectory)
                    .filter(p -> "native-sources".equals(String.valueOf(p.getFileName())))
                    .filter(p -> Files.isRegularFile(p.resolve("native-image.args")))
                    .findFirst()
                    .orElse(null);
        }
        if (found == null) {
            throw new IOException("quarkus.native.sources-only produced no native-sources/ under " + augmentOut
                    + " — the augment did not run a native build");
        }
        Files.createDirectories(dest);
        copyTree(found, dest);
        System.err.println("jk-quarkus-augment: native sources -> " + dest);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        // `.jk-*` is the plugin-scratch convention; it must not ride into a staged layout.
        PathUtil.copyTree(
                from,
                to,
                dir -> dir.getFileName() != null && dir.getFileName().toString().startsWith(".jk-"));
    }

    private static void deleteTree(Path root) throws IOException {
        PathUtil.deleteRecursivelyOrThrow(root);
    }

    private QuarkusAugmentMain() {}
}
