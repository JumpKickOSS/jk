// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.host.Os;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.PluginModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Pure path layout for module build outputs.
 *
 * <ul>
 * <li><strong>Standalone</strong> (or workspace root as the only unit): {@code <module>/target/}.
 * <li><strong>Workspace member</strong>: {@code <workspace>/target/<module-rel>/} (Mill-style
 * central out tree), where {@code module-rel} is the path relative to the workspace root
 * (e.g. {@code plugins/auditor}).
 * </ul>
 *
 * <p>Kotlinc and javac use separate dirs ({@code kotlin/} vs {@code classes/}) so Kotlin's
 * incremental prune cannot drop javac output; classes are merged into {@code classes/} after
 * compile. Apps put artifacts at the module target root; libraries under {@code lib/}.
 */
public final class BuildLayout {

    /**
     * The one directory name jk writes build output under, and the one name every "is this a build
     * output tree?" test compares against. Ignore sets, {@code jk clean}, the preflight memo and
     * the workspace file server all have to agree with {@link #moduleTargetDir}; when they spelled
     * it themselves, agreement was a coincidence that a rename would have quietly ended — a
     * scanner still walking {@code target/} while the builder wrote somewhere else.
     *
     * <p>Not every {@code "target"} in the tree is this one: a CLI parameter named {@code target},
     * javac's {@code -target}, a BSP request field and the {@code ${target}} interpolation variable
     * are separate vocabularies and must not borrow it.
     */
    public static final String TARGET = "target";

    /**
     * True when {@code artifact} is a file jk built into a {@link #TARGET} tree.
     *
     * <p>Anchored on the shape, not on the name. An ancestor called {@code target} is not enough:
     * jk's own test scratch lives at {@code target/<module>/tmp/…}, so once the forked test JVM's
     * temp root moved inside the build output, every {@code @TempDir} acquired a {@code target}
     * ancestor and a scratch file started reading as a workspace-built artifact. The anchor is a
     * {@code classes/} directory beside the file — a module output directory has one and a scratch
     * directory does not — which is a fact about the tree rather than about how a path is spelled.
     *
     * <p>The same class of defect as a containment test written with {@code startsWith}: a textual
     * ancestor is not a structural one, and the two agree right up until someone puts a directory
     * where the text did not expect it.
     */
    public static boolean isBuildOutput(Path artifact) {
        if (artifact == null) return false;
        Path dir = artifact.toAbsolutePath().normalize().getParent();
        if (dir == null || !Files.isDirectory(dir.resolve("classes"))) return false;
        for (Path cur = dir; cur != null; cur = cur.getParent()) {
            Path name = cur.getFileName();
            if (name != null && TARGET.equals(name.toString())) return true;
        }
        return false;
    }

    private final Path workspaceRoot;
    private final Path moduleRoot;
    private final String artifact;
    private final String version;
    /** True when the project declares a {@code main} class (i.e. it is an application). */
    private final boolean hasMain;
    /** True when on-disk plugin authoring files mark this module as a worker. */
    private final boolean pluginWorker;
    /** {@code [native].name} when set — the on-disk binary basename, not the Maven artifact id. */
    private final String nativeName;

    private BuildLayout(
            Path workspaceRoot,
            Path moduleRoot,
            String artifact,
            String version,
            boolean hasMain,
            boolean pluginWorker,
            String nativeName) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        this.moduleRoot = Objects.requireNonNull(moduleRoot, "moduleRoot");
        this.artifact = Objects.requireNonNull(artifact, "artifact");
        this.version = Objects.requireNonNull(version, "version");
        this.hasMain = hasMain;
        this.pluginWorker = pluginWorker;
        this.nativeName = nativeName;
    }

    public static BuildLayout of(Path projectDir, JkBuild project) {
        Objects.requireNonNull(projectDir, "projectDir");
        Objects.requireNonNull(project, "project");
        Path workspaceRoot = projectDir;
        if (!project.isWorkspaceRoot()) {
            try {
                workspaceRoot = WorkspaceLocator.findRoot(projectDir).orElse(projectDir);
            } catch (IOException ignored) {
            }
        }
        return new BuildLayout(
                workspaceRoot,
                projectDir,
                project.project().name(),
                project.project().version(),
                hasMain(project),
                PluginModule.isWorker(projectDir),
                nativeName(project));
    }

    public static BuildLayout of(Path workspaceRoot, Path moduleRoot, JkBuild project) {
        Objects.requireNonNull(project, "project");
        return new BuildLayout(
                workspaceRoot,
                moduleRoot,
                project.project().name(),
                project.project().version(),
                hasMain(project),
                PluginModule.isWorker(moduleRoot),
                nativeName(project));
    }

    private static String nativeName(JkBuild project) {
        return project.nativeConfig().map(JkBuild.NativeConfig::name).orElse(null);
    }

    private static boolean hasMain(JkBuild project) {
        return project.mainClass() != null;
    }

    // ---- Roots --------------------------------------------------------

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public Path moduleRoot() {
        return moduleRoot;
    }

    public String artifact() {
        return artifact;
    }

    public String version() {
        return version;
    }

    /** True when this project declares {@code main} — i.e. it is an application. */
    public boolean hasMain() {
        return hasMain;
    }

    /** True when this module is a plugin worker (see {@link cc.jumpkick.plugin.PluginModule}). */
    public boolean pluginWorker() {
        return pluginWorker;
    }

    /**
     * True when deliverables go at {@code target/} (application or plugin worker), not {@code
     * target/lib/}.
     */
    public boolean packagedAtRoot() {
        return hasMain || pluginWorker;
    }

    // ---- Per-module output -------------------------------------------------

    /** Memoized {@link #moduleTargetDir()} — every layout accessor funnels through it, and the
     * alias-fallback path costs filesystem walks; both roots are final, so the answer is stable
     * for the instance's life. */
    private volatile Path cachedModuleTargetDir;

    /**
     * Root of this module's output tree: {@code <module>/target/} when standalone (or the unit is
     * the workspace root itself); {@code <workspace>/target/<rel>/} for a workspace member.
     */
    public Path moduleTargetDir() {
        Path cached = cachedModuleTargetDir;
        if (cached == null) {
            cached = moduleTargetDir(workspaceRoot, moduleRoot);
            cachedModuleTargetDir = cached;
        }
        return cached;
    }

    /**
     * As {@link #moduleTargetDir} from the two roots alone — the layout decision needs no parsed
     * project, so callers on parse-free fast paths (preflight memo) share one rule.
     *
     * <p>Membership is decided <em>lexically</em> first (zero filesystem I/O on the hot path, and
     * a member symlinked <em>into</em> the workspace tree keeps its central out dir — realpath
     * would relocate its outputs to a module-local {@code target/}). Only on a lexical miss does
     * {@link #absoluteKey} reconcile symlink alias pairs ({@code /var} vs {@code /private/var} on
     * macOS). The returned path keeps the caller's {@code workspaceRoot} form (absolute +
     * normalize) so it matches other paths the caller already holds.
     */
    public static Path moduleTargetDir(Path workspaceRoot, Path moduleRoot) {
        Path wsOut = workspaceRoot.toAbsolutePath().normalize();
        Path modAbs = moduleRoot.toAbsolutePath().normalize();
        if (modAbs.equals(wsOut)) {
            return wsOut.resolve(TARGET);
        }
        if (modAbs.startsWith(wsOut)) {
            return wsOut.resolve(TARGET).resolve(wsOut.relativize(modAbs));
        }
        // Lexical miss: an alias pair can still name the same tree — compare realpath keys.
        Path modKey = absoluteKey(moduleRoot);
        Path wsKey = absoluteKey(workspaceRoot);
        if (modKey.equals(wsKey)) {
            return wsOut.resolve(TARGET);
        }
        if (!modKey.startsWith(wsKey)) {
            // Genuinely outside the workspace tree — fall back to module-local target/.
            return modAbs.resolve(TARGET);
        }
        return wsOut.resolve(TARGET).resolve(wsKey.relativize(modKey));
    }

    /**
     * Absolute path with existing symlink parents resolved, so macOS {@code /var} vs
     * {@code /private/var} (and similar alias pairs) compare equal for prefix checks.
     */
    static Path absoluteKey(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        try {
            if (Files.exists(abs)) {
                return abs.toRealPath();
            }
            // Reconstruct under the realpath of the deepest existing ancestor.
            Path cur = abs;
            ArrayDeque<String> missing = new ArrayDeque<>();
            while (cur != null && !Files.exists(cur)) {
                Path name = cur.getFileName();
                if (name != null) missing.push(name.toString());
                cur = cur.getParent();
            }
            if (cur == null) return abs;
            Path real = cur.toRealPath();
            while (!missing.isEmpty()) {
                real = real.resolve(missing.pop());
            }
            return real.normalize();
        } catch (IOException e) {
            return abs;
        }
    }

    /** Root of all per-module build intermediates (same as {@link #moduleTargetDir()}). */
    public Path buildDir() {
        return moduleTargetDir();
    }

    /**
     * {@code target/classes/main/} — final assembled main classes.
     *
     * <p>Both javac output and (after assembly) kotlinc output land here. This is the directory the
     * JAR packager reads from, so it contains all compiled classes regardless of which compiler
     * produced them. The Kotlin incremental compiler writes to {@link #kotlinClassesDir} first,
     * then jk merges the result here.
     */
    public Path classesDir() {
        return buildDir().resolve("classes").resolve("main");
    }

    /** {@code target/classes/test/} — final assembled test classes. */
    public Path testClassesDir() {
        return buildDir().resolve("classes").resolve("test");
    }

    /**
     * {@code target/test-fixtures/classes/} — fixtures compile output. A directory, never a jar, so
     * it cannot leak into a POM.
     */
    public Path testFixturesClassesDir() {
        return moduleTargetDir().resolve("test-fixtures").resolve("classes");
    }

    /**
     * {@code target/jdt/classes/main/} — main class output for an external IDE language server
     * (Eclipse JDT-LS, used by VS Code's redhat.java). Kept separate from {@link #classesDir} so an
     * IDE's continuous autobuild never collides with jk's incremental compiler, which deletes and
     * re-hashes every {@code .class} under its own output dir.
     */
    public Path jdtClassesDir() {
        // Module-LOCAL on purpose (unlike buildDir's Mill-style central tree): Eclipse JDT
        // requires output folders inside the project, and a workspace member's central dir would
        // render as an invalid "../target/…" entry in .classpath. jk's own outputs
        // never live here, so the isolation contract holds either way.
        return moduleRoot().resolve(TARGET).resolve("jdt").resolve("classes").resolve("main");
    }

    /** {@code target/jdt/classes/test/} — test class output for an external IDE language server. */
    public Path jdtTestClassesDir() {
        return moduleRoot().resolve(TARGET).resolve("jdt").resolve("classes").resolve("test");
    }

    /**
     * {@code target/kotlin/main/} — kotlinc incremental workspace for main sources.
     *
     * <p>The Kotlin BTA incremental compiler owns this directory and prunes any {@code .class} file
     * it did not produce. It must never share a dir with javac's output. After kotlinc finishes, jk
     * merges the output into {@link #classesDir}.
     */
    public Path kotlinClassesDir() {
        return buildDir().resolve("kotlin").resolve("main");
    }

    /** {@code target/kotlin/test/} — kotlinc incremental workspace for test sources. */
    public Path kotlinTestClassesDir() {
        return buildDir().resolve("kotlin").resolve("test");
    }

    /**
     * {@code target/groovy/main/} — groovyc output for main sources. Same merge-into-{@code
     * classes/} rationale as {@link #kotlinClassesDir}: the worker's action-cache snapshots its
     * whole output dir, so it must never share a dir with javac's output.
     */
    public Path groovyClassesDir() {
        return buildDir().resolve("groovy").resolve("main");
    }

    /** {@code target/groovy/test/} — groovyc output for test sources. */
    public Path groovyTestClassesDir() {
        return buildDir().resolve("groovy").resolve("test");
    }

    /** {@code target/groovy/stubs/} — Java-visible stubs retained by a joint Groovy compile. */
    public Path groovyStubsDir() {
        return buildDir().resolve("groovy").resolve("stubs");
    }

    /** {@code target/resources/main/} — copied main resources. */
    public Path resourcesDir() {
        return buildDir().resolve("resources").resolve("main");
    }

    /** {@code target/resources/test/} — copied test resources. */
    public Path testResourcesDir() {
        return buildDir().resolve("resources").resolve("test");
    }

    /** {@code target/generated/sources/<processor>/main/} — annotation-processor output. */
    public Path generatedSourcesDir(String processor) {
        return generatedSourcesDir(processor, "main");
    }

    /**
     * {@code target/generated/sources/<processor>/<sourceSet>/} — annotation-processor output for a
     * given source set ({@code main} or {@code test}). Test processing must not share a directory
     * with main, or the two would clobber each other's generated files.
     */
    public Path generatedSourcesDir(String processor, String sourceSet) {
        Objects.requireNonNull(processor, "processor");
        Objects.requireNonNull(sourceSet, "sourceSet");
        return buildDir()
                .resolve("generated")
                .resolve("sources")
                .resolve(processor)
                .resolve(sourceSet);
    }

    /** {@code target/tmp/} — scratch space safe to delete between runs. */
    public Path tmpDir() {
        return buildDir().resolve("tmp");
    }

    /** {@code target/reports/} — test and coverage reports. */
    public Path reportsDir() {
        return buildDir().resolve("reports");
    }

    /** {@code target/reports/<module>/} — JUnit reports for a workspace module. */
    public Path testReportsDir(String module) {
        Objects.requireNonNull(module, "module");
        return reportsDir().resolve(module);
    }

    /** {@code target/reports/test-results/} — JUnit XML test results. */
    public Path testResultsDir() {
        return reportsDir().resolve("test-results");
    }

    // Note: target/jk-results.md deliberately has no helper here. The real contract is
    // INVOCATION-root, not workspace-root — the engine writes it at the build request's dir
    // (JournalWriter.latestPath) and the CLI reads it at its own projectDir — and this class
    // only knows the workspace root, so a helper here would encode the wrong anchor.

    // ---- Final artifacts -------------------------------------------------------

    /**
     * Root of all build output for this module (alias of {@link #moduleTargetDir}).
     *
     * <p>In a workspace, all members write under {@code <workspace>/target/<module-rel>/} so the
     * monorepo has a single out tree (like Mill's {@code out/}).
     */
    public Path targetDir() {
        return moduleTargetDir();
    }

    /**
     * Destination directory for deliverable artifacts (jars, binaries, OCI images).
     *
     * <ul>
     * <li>{@code target/} when the project declares {@code main} or is a plugin worker —
     * the packaged output is a process entry (app or {@code PluginMain} worker).
     * <li>{@code target/lib/} when neither applies — a library whose packaged output is
     * consumed by other projects, not run directly.
     * </ul>
     *
     * <p>This rule also applies to native shared-library outputs ({@code .so}, {@code .dylib},
     * {@code .dll}) produced by GraalVM {@code native-image --shared}.
     */
    public Path artifactDir() {
        return packagedAtRoot() ? targetDir() : targetDir().resolve("lib");
    }

    /** {@code <artifactDir>/<artifact>-<version>.jar} — the main jar. */
    public Path mainJar() {
        return artifactDir().resolve(artifact + "-" + version + ".jar");
    }

    /** {@code <artifactDir>/<artifact>-<version>-all.jar} — the fat / shaded jar (deps included). */
    public Path assemblyJar() {
        return artifactDir().resolve(artifact + "-" + version + "-all.jar");
    }

    /** {@code <artifactDir>/<artifact>-<version>-min.jar} — the R8-minified jar. */
    public Path minifiedJar() {
        return artifactDir().resolve(artifact + "-" + version + "-min.jar");
    }

    /** {@code <artifactDir>/<artifact>-<version>-sources.jar}. */
    public Path sourcesJar() {
        return artifactDir().resolve(artifact + "-" + version + "-sources.jar");
    }

    /** {@code <artifactDir>/<artifact>-<version>-javadoc.jar}. */
    public Path javadocJar() {
        return artifactDir().resolve(artifact + "-" + version + "-javadoc.jar");
    }

    /**
     * On-disk GraalVM native executable. {@code [native].name} overrides the artifact id (the
     * binary may be {@code jk} while the module is {@code jk-cli}). Windows native-image writes
     * {@code .exe}; this path includes that suffix so cache store/restore and presence probes
     * look at the file that actually lands on disk.
     */
    public Path nativeBinary() {
        if (nativeName != null) {
            return moduleTargetDir().resolve(nativeExecutableFileName(nativeName));
        }
        return artifactDir().resolve(nativeExecutableFileName(artifact));
    }

    /**
     * On-disk native executable name for an {@code -o} basename. {@code jk} and {@code jk.exe}
     * are the same name; Windows gets {@code .exe}, other OS do not.
     */
    public static String nativeExecutableFileName(String base) {
        String name = JkBuild.NativeConfig.executableBasename(base);
        if (name == null || name.isEmpty()) return base;
        return Os.isWindows() ? name + ".exe" : name;
    }

    /**
     * {@code <artifactDir>/lib<artifact>} — base path for a GraalVM-compiled native shared library
     * ({@code native-image --shared}). This is the {@code -o} basename only; native-image appends
     * the platform extension ({@code .so}/{@code .dylib}/{@code .dll}) and emits C headers alongside.
     */
    public Path nativeLibrary() {
        return artifactDir().resolve("lib" + artifact);
    }

    /** {@code <artifactDir>/<artifact>.oci.tar} — default Jib OCI tarball. */
    public Path ociImageTar() {
        return artifactDir().resolve(artifact + ".oci.tar");
    }

    /** {@code <artifactDir>/<artifact>-<version>-sbom/} — CycloneDX / SPDX outputs. */
    public Path sbomDir() {
        return artifactDir().resolve(artifact + "-" + version + "-sbom");
    }

    /** {@code <artifactDir>/<artifact>-<version>-provenance/} — SLSA in-toto attestations. */
    public Path provenanceDir() {
        return artifactDir().resolve(artifact + "-" + version + "-provenance");
    }
}
