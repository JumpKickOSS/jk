// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.host.Os;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Pure path layout for module build outputs: Maven's. Every module, standalone or a workspace
 * member, writes to its own {@code <module>/target/}: {@code classes/} and {@code test-classes/}
 * (resources copied in), {@code generated-sources/} and {@code generated-test-sources/}, the
 * jars at the target root, {@code surefire-reports/} and {@code failsafe-reports/}, and {@code
 * site/} for javadoc and coverage. Directories Maven does not define ({@code kotlin/}, {@code
 * groovy/}, {@code incremental/}, {@code plugin/}, …) are jk's own working state.
 *
 * <p>Kotlinc and javac use separate dirs ({@code kotlin/} vs {@code classes/}) so Kotlin's
 * incremental prune cannot drop javac output; classes are merged into {@code classes/} after
 * compile.
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
     * scratch under {@code target/<module>/tmp/…} has one too, and must not read as a
     * workspace-built artifact. The anchor is a
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
    private final @Nullable String nativeName;

    private BuildLayout(
            Path workspaceRoot,
            Path moduleRoot,
            String artifact,
            String version,
            boolean hasMain,
            boolean pluginWorker,
            @Nullable String nativeName) {
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

    private static @Nullable String nativeName(JkBuild project) {
        return project.nativeConfigOpt().map(JkBuild.NativeConfig::name).orElse(null);
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

    /** True when this module is a plugin worker (see {@link PluginModule}). */
    public boolean pluginWorker() {
        return pluginWorker;
    }

    /** True for an application or a plugin worker: a deliverable that runs, rather than a library. */
    public boolean packagedAtRoot() {
        return hasMain || pluginWorker;
    }

    // ---- Per-module output -------------------------------------------------

    /** Root of this module's output tree: {@code <module>/target/}. */
    public Path moduleTargetDir() {
        return moduleTargetDir(moduleRoot);
    }

    /** Where the module's plugin steps and commands keep their scratch: {@code <target>/plugin/}. */
    public Path pluginDir() {
        return moduleTargetDir().resolve("plugin");
    }

    /**
     * One plugin step's scratch root, {@code <target>/plugin/<step>/}: its declared output dirs
     * resolve under it — a lint step's report at {@code lint/<tool>/<tool>.xml}.
     */
    public Path pluginStepScratch(String step) {
        return pluginDir().resolve(step);
    }

    /** {@code <module>/target/} — the one rule, for callers that hold only the module directory. */
    public static Path moduleTargetDir(Path moduleRoot) {
        return moduleRoot.toAbsolutePath().normalize().resolve(TARGET);
    }

    /** Root of all per-module build intermediates (same as {@link #moduleTargetDir()}). */
    public Path buildDir() {
        return moduleTargetDir();
    }

    /**
     * {@code target/classes/} — final assembled main classes, main resources copied in.
     *
     * <p>Both javac output and (after assembly) kotlinc output land here. This is the directory the
     * JAR packager reads from, so it contains all compiled classes regardless of which compiler
     * produced them. The Kotlin incremental compiler writes to {@link #kotlinClassesDir} first,
     * then jk merges the result here.
     */
    public Path classesDir() {
        return buildDir().resolve(CLASSES);
    }

    /**
     * Where every main-compile freshness stamp ({@link cc.jumpkick.host.BuildStamps#JAVA}, {@code
     * KOTLIN}, {@code GROOVY}) is written and read: the merged classes tree, beside javac's output.
     * The write-stamp steps drop each stamp into the compile's own output — {@link #classesDir} —
     * and every forecast that asks whether a compile is stamp-fresh reads from here. kotlinc's and
     * groovyc's private output dirs ({@link #kotlinClassesDir}, {@link #groovyClassesDir}) never
     * carry a stamp: a reader that looks there finds none and prices every build as a compile.
     */
    public Path compileStampDir() {
        return classesDir();
    }

    /**
     * {@code target/versions/} — the {@code [multi-release]} compiles' outputs, laid out as the jar
     * carries them: {@code META-INF/versions/<N>/} under this root, which packaging merges over
     * {@link #classesDir}.
     */
    public Path versionedClassesRoot() {
        return buildDir().resolve("versions");
    }

    /** {@code target/versions/META-INF/versions/<release>/} — one {@code [multi-release]} compile's output. */
    public Path versionedClassesDir(int release) {
        return versionedClassesRoot().resolve("META-INF").resolve("versions").resolve(Integer.toString(release));
    }

    /** {@code target/test-classes/} — final assembled test classes, test resources copied in. */
    public Path testClassesDir() {
        return buildDir().resolve(TEST_CLASSES);
    }

    private static final String CLASSES = "classes";
    private static final String TEST_CLASSES = "test-classes";

    /**
     * The classes directory a compile-classpath entry was compiled into, when the entry is one jk
     * built: a {@link #classesDir} or {@link #testClassesDir} is its own answer, and a jar under a
     * module's {@link #artifactDir} was packaged from that module's {@link #classesDir}. Empty for
     * everything else — a Maven jar, the JDK, another build tool's output.
     *
     * <p>Anchored on the tree's shape like {@link #isBuildOutput}: the jar's directory is the
     * module's target dir, and the classes tree has to exist beside it. The
     * answer names the directory a compile's incremental state is keyed by, which is how a
     * consumer finds its producer's state from the entry alone — the same way whether the
     * classpath carries the producer's jar or its classes directory.
     */
    public static Optional<Path> compiledClassesOf(Path entry) {
        Path abs = entry.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        if (parent == null) return Optional.empty();
        String name = String.valueOf(abs.getFileName());
        if (Files.isDirectory(abs)) {
            boolean classesTree = (CLASSES.equals(name) || TEST_CLASSES.equals(name))
                    && TARGET.equals(String.valueOf(parent.getFileName()));
            return classesTree ? Optional.of(abs) : Optional.empty();
        }
        if (!name.endsWith(".jar")) return Optional.empty();
        Path classes = parent.resolve(CLASSES);
        return Files.isDirectory(classes) ? Optional.of(classes) : Optional.empty();
    }

    /**
     * {@code target/test-fixtures/classes/} — fixtures compile output. A directory, never a jar, so
     * it cannot leak into a POM.
     */
    public Path testFixturesClassesDir() {
        return moduleTargetDir().resolve("test-fixtures").resolve("classes");
    }

    /**
     * {@code target/guard/classes/} — the guard suite's compile output. A directory, never a jar:
     * guard tests are run by the guard lanes and reach no publication.
     */
    public Path guardClassesDir() {
        return moduleTargetDir().resolve("guard").resolve("classes");
    }

    /**
     * {@code target/jdt/classes/main/} — main class output for an external IDE language server
     * (Eclipse JDT-LS, used by VS Code's redhat.java). Kept separate from {@link #classesDir} so an
     * IDE's continuous autobuild never collides with jk's incremental compiler, which deletes and
     * re-hashes every {@code .class} under its own output dir.
     */
    public Path jdtClassesDir() {
        return moduleTargetDir().resolve("jdt").resolve("classes").resolve("main");
    }

    /** {@code target/jdt/classes/test/} — test class output for an external IDE language server. */
    public Path jdtTestClassesDir() {
        return moduleTargetDir().resolve("jdt").resolve("classes").resolve("test");
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

    /** {@code target/generated-sources/<processor>/} — annotation-processor output for main sources. */
    public Path generatedSourcesDir(String processor) {
        return generatedSourcesDir(processor, "main");
    }

    /**
     * Annotation-processor output for a source set, as Maven lays it out: {@code
     * target/generated-sources/<processor>/} for {@code main}, {@code
     * target/generated-test-sources/test-<processor>/} for {@code test}, and {@code
     * target/generated-sources/<processor>-<sourceSet>/} for jk's other sets (fixtures, guard,
     * multi-release). Each set has its own directory, so one set's generated files never clobber
     * another's.
     */
    public Path generatedSourcesDir(String processor, String sourceSet) {
        Objects.requireNonNull(processor, "processor");
        Objects.requireNonNull(sourceSet, "sourceSet");
        return switch (sourceSet) {
            case "main" -> buildDir().resolve("generated-sources").resolve(processor);
            case "test" -> buildDir().resolve("generated-test-sources").resolve("test-" + processor);
            default -> buildDir().resolve("generated-sources").resolve(processor + "-" + sourceSet);
        };
    }

    /** {@code target/reports/} — jk's own reports (the native-image log, …); Maven defines none here. */
    public Path reportsDir() {
        return buildDir().resolve("reports");
    }

    /** {@code target/surefire-reports/} — JUnit XML for every suite but {@code integration}, as Surefire writes it. */
    public Path testResultsDir() {
        return buildDir().resolve("surefire-reports");
    }

    /** {@code target/failsafe-reports/} — JUnit XML for the {@code integration} suite, as Failsafe writes it. */
    public Path integrationResultsDir() {
        return buildDir().resolve("failsafe-reports");
    }

    /** {@code target/site/} — generated documentation and report sites. */
    public Path siteDir() {
        return buildDir().resolve("site");
    }

    /** {@code target/site/apidocs/} — javadoc (or Dokka) HTML, as the javadoc plugin writes it. */
    public Path apidocsDir() {
        return siteDir().resolve("apidocs");
    }

    /** {@code target/jacoco.exec} — the JaCoCo agent's execution data. */
    public Path jacocoExec() {
        return buildDir().resolve("jacoco.exec");
    }

    /** {@code target/site/jacoco/} — the JaCoCo report: {@code index.html}, {@code jacoco.xml}. */
    public Path jacocoReportDir() {
        return siteDir().resolve("jacoco");
    }

    // Note: target/jk-results.md deliberately has no helper here. The real contract is
    // INVOCATION-root, not workspace-root — the engine writes it at the build request's dir
    // (JournalWriter.latestPath) and the CLI reads it at its own projectDir — and this class
    // only knows the workspace root, so a helper here would encode the wrong anchor.

    // ---- Final artifacts -------------------------------------------------------

    /** Root of all build output for this module (alias of {@link #moduleTargetDir}). */
    public Path targetDir() {
        return moduleTargetDir();
    }

    /**
     * Destination directory for deliverable artifacts (jars, binaries, OCI images): the module's
     * {@code target/}, as Maven's.
     */
    public Path artifactDir() {
        return targetDir();
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
        return artifactDir().resolve(nativeExecutableFileName(nativeName != null ? nativeName : artifact));
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

    /** {@code <moduleTargetDir>/sbom/} — the CycloneDX and SPDX documents {@code jk publish --sbom} writes. */
    public Path sbomDir() {
        return moduleTargetDir().resolve("sbom");
    }

    /** {@code <artifactDir>/<artifact>-<version>-provenance/} — SLSA in-toto attestations. */
    public Path provenanceDir() {
        return artifactDir().resolve(artifact + "-" + version + "-provenance");
    }
}
