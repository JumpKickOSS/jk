// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.layout.InputTrees;
import cc.jumpkick.layout.Languages;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.SourceLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.Profiles;
import cc.jumpkick.model.Project;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Pure source-set helpers shared by the build plan and the git-source builder. Extracted out of
 * the CLI's {@code CompileCommand} so embedders can drive compilation inputs without depending on
 * {@code :cli}.
 */
public final class CompileSupport {

    /**
     * {@code a} then {@code b}, dropping repeats. The dedup is what matters: a path listed
     * twice appends its {@code source:} token twice and moves the action key, while
     * {@code ActionKey.appendSources} sorts, so the order this returns them in does not.
     */
    public static List<Path> concatDistinct(List<Path> a, List<Path> b) {
        if (b == null || b.isEmpty()) return a == null ? List.of() : a;
        List<Path> out = new ArrayList<>(a == null ? List.of() : a);
        for (Path p : b) {
            if (!out.contains(p)) out.add(p);
        }
        return out;
    }

    private CompileSupport() {}

    /** One shared language answer for engine lanes and the resolver inject. */
    public static Languages resolveLanguages(Project project, Path projectDir) {
        return Languages.resolve(project, projectDir);
    }

    /** True if {@code projectDir} contains any Java, Kotlin, Groovy, or Scala source files. */
    public static boolean hasSources(Path projectDir) {
        return Files.isDirectory(projectDir.resolve("src/main/java"))
                || Files.isDirectory(projectDir.resolve("src/main/kotlin"))
                || Files.isDirectory(projectDir.resolve("src/main/groovy"))
                || Files.isDirectory(projectDir.resolve("src/main/scala"))
                || Languages.anySourceUnder(projectDir.resolve("src"), ".java")
                || Languages.anySourceUnder(projectDir.resolve("src"), ".kt")
                || Languages.anySourceUnder(projectDir.resolve("src"), ".groovy")
                || Languages.anySourceUnder(projectDir.resolve("src"), ".scala");
    }

    /**
     * A workspace root that carries no sources: it coordinates members and compiles, tests and
     * packages nothing itself.
     *
     * <p>One answer, because two planners ask it and a disagreement is a broken plan rather than a
     * wrong number. {@code coreBuilder} stops such a unit after its build logic, and {@code
     * appendDeclaredTails} must then not hang an assembly / native / sources tail off a {@code
     * package-jar} that is not there — the same reason it returns early for {@code jk compile}.
     */
    public static boolean coordinatorOnly(JkBuild project, Path projectDir) {
        return project.isWorkspaceRoot() && !hasSources(projectDir);
    }

    /** Whether this project uses the flat ({@code src/}/{@code test/}) layout. */
    public static boolean isSimpleLayout(Project project, Path projectDir) {
        return SourceLayout.isSimpleLayout(project, projectDir);
    }

    /**
     * Path-only layout probe: honors {@code layout =} in {@code jk.toml} when present, else the
     * tree. Prefer {@link #isSimpleLayout(Project, Path)} when the project is already
     * parsed (workspace inheritance).
     */
    public static boolean isSimpleLayout(Path projectDir) {
        return ModuleLayout.isCompact(projectDir);
    }

    /** All {@code .java} files under {@code root} (empty if it doesn't exist). */
    public static List<Path> collectJavaSources(Path root) throws IOException {
        return collectFilesWithExtension(root, ".java");
    }

    /**
     * All main {@code .kt} files for a project.
     *
     * <ul>
     * <li>Standard layout: {@code src/main/kotlin/} and {@code src/main/java/}
     * <li>Compact layout: {@code src/} (all {@code .kt} files)
     * </ul>
     */
    public static List<Path> collectKotlinSources(Path projectDir, boolean compact) throws IOException {
        if (compact) {
            return collectFilesWithExtension(projectDir.resolve("src"), ".kt");
        }
        List<Path> out = new ArrayList<>();
        out.addAll(collectFilesWithExtension(projectDir.resolve("src/main/kotlin"), ".kt"));
        out.addAll(collectFilesWithExtension(projectDir.resolve("src/main/java"), ".kt"));
        return out;
    }

    /**
     * All test {@code .kt} files for a project.
     *
     * <ul>
     * <li>Standard layout: {@code src/test/kotlin/} and {@code src/test/java/}
     * <li>Compact (Mill-like) layout: {@code test/src/} (all {@code .kt} files)
     * </ul>
     */
    public static List<Path> collectKotlinTestSources(Path projectDir, boolean compact) throws IOException {
        if (compact) {
            return collectFilesWithExtension(projectDir.resolve("test").resolve("src"), ".kt");
        }
        List<Path> out = new ArrayList<>();
        out.addAll(collectFilesWithExtension(projectDir.resolve("src/test/kotlin"), ".kt"));
        out.addAll(collectFilesWithExtension(projectDir.resolve("src/test/java"), ".kt"));
        return out;
    }

    /**
     * All main {@code .groovy} files for a project — roots from {@link
     * cc.jumpkick.layout.ModuleLayout#mainGroovyRoots} (SIMPLE shares {@code src/} by extension).
     */
    public static List<Path> collectGroovySources(Path projectDir, boolean compact) throws IOException {
        var out = new LinkedHashSet<Path>();
        for (Path root : ModuleLayout.mainGroovyRoots(projectDir, compact)) {
            out.addAll(collectFilesWithExtension(root, ".groovy"));
        }
        return new ArrayList<>(out);
    }

    /** All default-suite test {@code .groovy} files (roots from {@link cc.jumpkick.layout.TestSuites}). */
    public static List<Path> collectGroovyTestSources(Path projectDir, boolean compact) throws IOException {
        return TestSuites.collectGroovySources(projectDir, compact, List.of(TestSuites.DEFAULT));
    }

    /**
     * All main {@code .scala} files for a project — roots from {@link
     * cc.jumpkick.layout.ModuleLayout#mainScalaRoots} (SIMPLE shares {@code src/} by extension).
     */
    public static List<Path> collectScalaSources(Path projectDir, boolean compact) throws IOException {
        var out = new LinkedHashSet<Path>();
        for (Path root : ModuleLayout.mainScalaRoots(projectDir, compact)) {
            out.addAll(collectFilesWithExtension(root, ".scala"));
        }
        return new ArrayList<>(out);
    }

    /** All default-suite test {@code .scala} files (roots from {@link cc.jumpkick.layout.TestSuites}). */
    public static List<Path> collectScalaTestSources(Path projectDir, boolean compact) throws IOException {
        return TestSuites.collectScalaSources(projectDir, compact, List.of(TestSuites.DEFAULT));
    }

    /**
     * {@code [build] extra-src} roots resolved against the module dir, extant dirs only — the
     * core per-variant source-dir mechanism (variant overlays append to {@code extra-src};
     * {@code VariantApply} folds them before this runs). Missing dirs are fine: a variant that
     * declares {@code src/demo/kotlin} doesn't force the dir to exist.
     */
    public static List<Path> extraSrcDirs(JkBuild project, Path projectDir) {
        List<Path> out = new ArrayList<>();
        for (String rel : project.build().extraSrc()) {
            Path dir = projectDir.resolve(rel);
            if (Files.isDirectory(dir)) out.add(dir);
        }
        return out;
    }

    /** {@code base} plus every {@code extension} file under {@code extraDirs}, deduplicated. */
    public static List<Path> withExtraSources(@Nullable List<Path> base, List<Path> extraDirs, String extension)
            throws IOException {
        List<Path> from = base == null ? List.of() : base;
        if (extraDirs.isEmpty()) return from;
        var all = new LinkedHashSet<>(from);
        for (Path dir : extraDirs) all.addAll(collectFilesWithExtension(dir, extension));
        return new ArrayList<>(all);
    }

    /**
     * The {@code -jvm-target} kotlinc should use for a given Java release. Kotlin tops out at 21
     * today; targeting a newer JDK is fine because Java is bytecode-backward-compatible.
     */
    /** The newest {@code -jvm-target} the Kotlin compiler accepts. */
    public static final int KOTLIN_MAX_JVM_TARGET = 21;

    /**
     * The Kotlin {@code -jvm-target} for a project at {@code release}, where {@code 0} means the
     * manifest declares no level and the JDK the build runs on ({@code hostFeature}) is the level —
     * the same reading javac gives an absent {@code --release}.
     */
    public static int kotlinJvmTarget(int release, int hostFeature) {
        return Math.min(effectiveRelease(release, hostFeature), KOTLIN_MAX_JVM_TARGET);
    }

    /** {@code release} when the manifest declares one, else the build JDK's feature major. */
    public static int effectiveRelease(int release, int hostFeature) {
        return release > 0 ? release : hostFeature;
    }

    /**
     * Pick the active profile. Explicit {@code explicitName} wins; otherwise the {@code ci} profile
     * is auto-selected when running on CI. Returns null when no profile applies.
     */
    public static @Nullable Profile resolveProfile(Profiles profiles, @Nullable String explicitName) {
        if (explicitName != null && !explicitName.isBlank()) {
            return profiles.resolve(explicitName);
        }
        String auto = Profiles.autoSelect(System.getenv());
        if (auto != null && profiles.contains(auto)) {
            return profiles.resolve(auto);
        }
        return null;
    }

    private static List<Path> collectFilesWithExtension(Path root, String extension) throws IOException {
        // Once per (root, extension) per request. The Groovy and Scala root sets deliberately
        // include the Java root — a stray.groovy under src/main/java must still compile —
        // and TaskForecaster runs the Java, Kotlin and Groovy collectors before it resolves which
        // languages the module actually uses. So src/main/java was walked four times per forecast
        // pass and src/test/java five, and the pass itself repeats across forecast, pricing, plan
        // assembly and the test lane.
        //
        // Request-scoped, so there is nothing to invalidate: a source tree is fixed for the length of
        // the build it was launched against, and a jk watch iteration is a new request with a new
        // scope.
        return InputTrees.of(root).withExtension(extension);
    }
}
