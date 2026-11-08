// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PomMetadata;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.TestJvm;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.mvn.NullnessImport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * What a Gradle project's tasks and publications say about one module beyond its dependencies:
 * the artifact id it publishes under, its javac arguments (Error Prone and NullAway included), its
 * test JVM and its POM metadata.
 */
final class GradleModuleSettings {

    private GradleModuleSettings() {}

    /**
     * The artifact id the build publishes {@code p} under: its one {@code MavenPublication}'s
     * {@code artifactId}, else {@code base.archivesName}, else the project name. Several
     * publications are a row, and the project keeps its archives name.
     */
    static String artifactName(GradleModel.Project p, ImportReport.Builder report) {
        List<String> ids = p.publications().stream()
                .map(GradleModel.Publication::artifactId)
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
        if (ids.size() == 1) return ids.getFirst();
        if (ids.size() > 1) {
            report.warning(p.path() + " publishes " + ids.size() + " artifacts (" + String.join(", ", ids)
                    + "); kept only the one named after its archives");
        }
        String archives = p.archivesName();
        return archives == null || archives.isBlank() ? p.name() : archives;
    }

    /**
     * {@code compileJava}'s javac arguments as {@code [javac] args}, with a {@code [javac.test]}
     * table when {@code compileTestJava}'s differ; arguments jk sets itself are left out. An Error
     * Prone plugin argument brings the {@code errorprone} configuration onto the processor path, and
     * NullAway run alone through it becomes {@code [nullness]}, as a POM's does.
     */
    static JkBuild compilerArgs(GradleModel.Project p, JkBuild build, ImportReport.Builder local) {
        List<String> main = p.mainCompilerArgs() == null ? List.of() : ownedByJk(p.mainCompilerArgs());
        List<String> test = p.testCompilerArgs() == null ? main : ownedByJk(p.testCompilerArgs());
        if (main.isEmpty() && test.isEmpty()) return build;
        JavacConfig javac = main.equals(test)
                ? new JavacConfig(Map.of(), main)
                : new JavacConfig(Map.of(), main, new JavacConfig(Map.of(), test));
        build = build.withBuild(build.build().withJavac(javac));
        boolean errorProne = main.stream().anyMatch(a -> a.startsWith(ERROR_PRONE_PLUGIN))
                || test.stream().anyMatch(a -> a.startsWith(ERROR_PRONE_PLUGIN));
        if (!errorProne) return build;
        List<Dependency> tools = new ArrayList<>();
        for (GradleModel.Configuration c : p.configurations()) {
            if (!c.name().equals("errorprone")) continue;
            for (GradleModel.Dependency d : c.dependencies()) {
                if (!d.kind().equals("module") || d.effectiveVersion().isBlank()) continue;
                tools.add(Dependency.of(d.artifact(), d.module(), VersionSelector.parse(d.effectiveVersion())));
            }
        }
        if (!tools.isEmpty()) {
            EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
            build.dependencies().byScope().forEach(byScope::put);
            List<Dependency> processor = new ArrayList<>(byScope.getOrDefault(Scope.PROCESSOR, List.of()));
            for (Dependency tool : tools) {
                if (processor.stream().noneMatch(d -> d.module().equals(tool.module()))) processor.add(tool);
            }
            byScope.put(Scope.PROCESSOR, processor);
            build = build.withDependencyScopes(byScope);
        }
        return NullnessImport.map(build, local);
    }

    private static final String ERROR_PRONE_PLUGIN = "-Xplugin:ErrorProne";

    /** Arguments that take the next one as their value, and that jk passes itself. */
    private static final Set<String> JK_OWNED_WITH_VALUE = Set.of(
            "--release",
            "-source",
            "--source",
            "-target",
            "--target",
            "-encoding",
            "-d",
            "-s",
            "-h",
            "-classpath",
            "-cp",
            "--class-path",
            "-processorpath",
            "--processor-path",
            "--module-path",
            "-p",
            "--processor-module-path",
            "-sourcepath",
            "--source-path");

    /** {@code args} without what jk sets itself: the level, the encoding, the paths and output directories. */
    private static List<String> ownedByJk(List<String> args) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (JK_OWNED_WITH_VALUE.contains(a)) {
                i++;
            } else if (!a.startsWith("--release=") && !a.startsWith("-Xlint:-options")) {
                out.add(a);
            }
        }
        return out;
    }

    /** The {@code test} task's JVM arguments and system properties as {@code [test] jvm-args} and {@code system-properties}. */
    static JkBuild testJvm(GradleModel.Project p, JkBuild build) {
        GradleModel.TestTask test = p.test();
        if (test == null || test.jvmArgs().isEmpty() && test.systemProperties().isEmpty()) return build;
        Map<String, String> properties = new TreeMap<>(test.systemProperties());
        return build.withBuild(build.build().withTestJvm(new TestJvm(test.jvmArgs(), properties)));
    }

    /** The one publication's POM description; null with none or several. */
    static @Nullable String pomDescription(GradleModel.Project p) {
        return p.publications().size() == 1 ? p.publications().getFirst().pomDescription() : null;
    }

    /** The one publication's POM name and url as {@code [publish]}. */
    static JkBuild publication(GradleModel.Project p, JkBuild build) {
        if (p.publications().size() != 1) return build;
        GradleModel.Publication pub = p.publications().getFirst();
        if (pub.pomName() == null && pub.pomUrl() == null) return build;
        return build.withPublish(
                new PomMetadata(pub.pomName(), null, pub.pomUrl(), List.of(), List.of(), null, null, false, null));
    }

    /**
     * A row when the module declares JSpecify and marks a package {@code @NullMarked} but runs no
     * null checker: {@code [nullness] include = []} checks exactly the marked code.
     */
    static void suggestNullness(GradleModel.Project p, Path dir, JkBuild build, ImportReport.Builder local) {
        if (build.build().nullness() != null) return;
        boolean jspecify = build.dependencies().byScope().values().stream()
                .flatMap(List::stream)
                .anyMatch(d -> "org.jspecify:jspecify".equals(d.module()));
        if (!jspecify || !marksNullMarked(p, dir)) return;
        local.warning("@NullMarked code but no null checker; add [nullness] include = [] to check it");
    }

    /** Whether a {@code package-info.java} under the project's main Java roots says {@code @NullMarked}. */
    private static boolean marksNullMarked(GradleModel.Project p, Path dir) {
        List<Path> roots = new ArrayList<>();
        for (GradleModel.SourceSet s : p.sourceSets()) {
            if (s.name().equals("main")) s.java().forEach(r -> roots.add(dir.resolve(r)));
        }
        if (roots.isEmpty()) roots.add(dir.resolve("src/main/java"));
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try {
                if (PathUtil.anyRegularFile(
                        root,
                        d -> false,
                        f -> f.getFileName().toString().equals("package-info.java") && saysNullMarked(f))) {
                    return true;
                }
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean saysNullMarked(Path packageInfo) {
        try {
            return Files.readString(packageInfo).contains("@NullMarked");
        } catch (IOException e) {
            return false;
        }
    }
}
