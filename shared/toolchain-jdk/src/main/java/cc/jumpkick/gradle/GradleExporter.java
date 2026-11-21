// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Layout;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.WorkspaceMerge;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Renders a {@link JkBuild} (and workspace) as a Gradle Kotlin-DSL build: {@code
 * settings.gradle.kts} plus a {@code build.gradle.kts} per project. The export-direction companion
 * to the importer's {@code GradleImporter}.
 *
 * <p>Versions come from {@code jk-lock.toml} when present ({@code locked} maps the lock's package
 * id, {@code group:artifact:jar:classifier}, → exact version), so the generated build reproduces
 * what jk builds; otherwise the declared selector collapses with a warning. A workspace sibling is a
 * {@code project(":path")} reference. {@code jdk} maps to a Gradle Java toolchain, with the {@code
 * foojay-resolver-convention} settings plugin enabling auto-download.
 */
public final class GradleExporter {

    // Plugin versions baked into generated builds — bump as upstreams release.
    private static final String FOOJAY = "0.9.0"; // org.gradle.toolchains.foojay-resolver-convention
    private static final String SHADOW = "8.3.6"; // com.gradleup.shadow
    private static final String NATIVE = "0.10.3"; // org.graalvm.buildtools.native

    /**
     * {@code settings} = settings.gradle.kts; {@code buildFiles} maps a project-relative dir ("" =
     * root) to its build.gradle.kts.
     */
    public record Result(String settings, Map<String, String> buildFiles, ImportReport report) {}

    private GradleExporter() {}

    /** Single-project export (auto layout). */
    public static Result export(JkBuild project, Map<String, String> locked) {
        return export(project, Map.of(), Map.of(), locked);
    }

    /** Workspace export with auto layout for every project. */
    public static Result export(JkBuild root, Map<String, JkBuild> modulesByRelPath, Map<String, String> locked) {
        return export(root, modulesByRelPath, Map.of(), locked);
    }

    /**
     * Export a project or workspace. {@code modulesByRelPath} (empty for a single project) maps each
     * module's path relative to the root to its parsed build; {@code layoutByRelPath} carries the
     * concrete {@link Layout} for each project keyed the same way (root = {@code ""}), so
     * jk's flat {@code SIMPLE} layout emits a matching {@code sourceSets} block. Missing entries
     * default to traditional (Maven source sets).
     */
    public static Result export(
            JkBuild root,
            Map<String, JkBuild> modulesByRelPath,
            Map<String, Layout> layoutByRelPath,
            Map<String, String> locked) {
        if (locked == null) locked = Map.of();
        if (layoutByRelPath == null) layoutByRelPath = Map.of();
        ImportReport.Builder report = ImportReport.builder();
        Map<String, String> buildFiles = new LinkedHashMap<>();

        String settings = renderSettings(root, modulesByRelPath.keySet());
        Map<String, String> projectPaths = new LinkedHashMap<>();
        for (Map.Entry<String, JkBuild> e : modulesByRelPath.entrySet()) {
            Project p = e.getValue().project();
            projectPaths.put(p.group() + ":" + p.name(), ":" + e.getKey().replace('/', ':'));
        }
        Exporting exporting = new Exporting(projectPaths, locked, report);
        buildFiles.put("", renderBuild(member(root, root, modulesByRelPath), layoutOf(layoutByRelPath, ""), exporting));
        for (Map.Entry<String, JkBuild> e : modulesByRelPath.entrySet()) {
            buildFiles.put(
                    e.getKey(),
                    renderBuild(
                            member(root, e.getValue(), modulesByRelPath),
                            layoutOf(layoutByRelPath, e.getKey()),
                            exporting));
        }
        return new Result(settings, buildFiles, report.build());
    }

    /** What every build file of one export reads: sibling {@code group:artifact} → Gradle project path, the lock, the report. */
    private record Exporting(
            Map<String, String> projectPaths, Map<String, String> locked, ImportReport.Builder report) {}

    /** {@code build} with its {@code workspace = true} edges naming the sibling's or the shared entry's coordinate. */
    private static JkBuild member(JkBuild root, JkBuild build, Map<String, JkBuild> modulesByRelPath) {
        return modulesByRelPath.isEmpty()
                ? build
                : WorkspaceMerge.resolveSiblingCoordinates(root, build, modulesByRelPath.values());
    }

    private static Layout layoutOf(Map<String, Layout> map, String key) {
        Layout l = map.get(key);
        return l != null ? l : Layout.TRADITIONAL;
    }

    private static String renderSettings(JkBuild root, Set<String> moduleRelPaths) {
        StringBuilder sb = new StringBuilder();
        sb.append("plugins {\n");
        sb.append("    // Auto-provisions JDK toolchains via the foojay Disco API (jk's `project.jdk`).\n");
        sb.append("    id(\"org.gradle.toolchains.foojay-resolver-convention\") version \"")
                .append(FOOJAY)
                .append("\"\n");
        sb.append("}\n\n");
        sb.append("rootProject.name = \"").append(kEsc(root.project().name())).append("\"\n");

        if (!moduleRelPaths.isEmpty()) {
            sb.append('\n');
            for (String rel : moduleRelPaths) {
                sb.append("include(\":").append(kEsc(rel.replace('/', ':'))).append("\")\n");
            }
        }
        return sb.toString();
    }

    private static String renderBuild(JkBuild jk, Layout layout, Exporting exporting) {
        ImportReport.Builder report = exporting.report();
        Project p = jk.project();
        VersionSelector kotlinVersion = p.kotlin();
        boolean kotlin = kotlinVersion != null;
        boolean app = jk.mainClass() != null;
        boolean assembly = jk.assembly();
        boolean nativeImg = jk.nativeMode() == JkBuild.NativeMode.ALWAYS;

        StringBuilder sb = new StringBuilder();
        sb.append("plugins {\n");
        // The api configuration exists only under java-library.
        sb.append(jk.dependencies().of(Scope.EXPORT).isEmpty() ? "    java\n" : "    `java-library`\n");
        if (kotlin) {
            sb.append("    kotlin(\"jvm\") version \"")
                    .append(kEsc(extractVersion(Objects.requireNonNull(kotlinVersion), "kotlin", report)))
                    .append("\"\n");
        }
        if (jk.build().war() != null) sb.append("    war\n");
        if (app) sb.append("    application\n");
        // Map jk assemble packaging onto Gradle's Shadow plugin (export only).
        if (assembly)
            sb.append("    id(\"com.gradleup.shadow\") version \"")
                    .append(SHADOW)
                    .append("\"\n");
        if (nativeImg)
            sb.append("    id(\"org.graalvm.buildtools.native\") version \"")
                    .append(NATIVE)
                    .append("\"\n");
        sb.append("}\n\n");

        sb.append("group = \"").append(kEsc(p.group())).append("\"\n");
        sb.append("version = \"").append(kEsc(p.version())).append("\"\n\n");

        appendRepositories(sb, jk.repositories());
        appendDependencies(sb, jk, exporting);
        appendToolchain(sb, p);
        appendSourceSets(sb, layout, kotlin);
        if (app) {
            sb.append("\napplication {\n    mainClass = \"")
                    .append(kEsc(jk.mainClass()))
                    .append("\"\n}\n");
        }
        appendManifest(sb, jk.manifest());
        warnDropped(jk, report);
        return sb.toString();
    }

    private static void appendRepositories(StringBuilder sb, List<RepositorySpec> repos) {
        sb.append("repositories {\n    mavenCentral()\n");
        for (RepositorySpec r : repos) {
            String u = r.url().toString();
            if (u.contains("repo.maven.apache.org") || u.contains("repo1.maven.org")) continue; // == mavenCentral
            sb.append("    maven { url = uri(\"").append(kEsc(u)).append("\") }\n");
        }
        sb.append("}\n\n");
    }

    private static void appendDependencies(StringBuilder sb, JkBuild jk, Exporting exporting) {
        Map<String, String> locked = exporting.locked();
        ImportReport.Builder report = exporting.report();
        // jk scope → Gradle configuration (inverse of GradleImporter.mapConfiguration).
        record Pair(Scope scope, String config) {}
        Pair[] order = {
            new Pair(Scope.EXPORT, "api"),
            new Pair(Scope.MAIN, "implementation"),
            new Pair(Scope.PROVIDED, "compileOnly"),
            new Pair(Scope.RUNTIME, "runtimeOnly"),
            new Pair(Scope.PROCESSOR, "annotationProcessor"),
            new Pair(Scope.TEST_PROCESSOR, "testAnnotationProcessor"),
            new Pair(Scope.TEST, "testImplementation"),
        };
        sb.append("dependencies {\n");
        for (Dependency d : jk.dependencies().of(Scope.PLATFORM)) {
            if (warnIfUnmappable(d, report)) continue;
            sb.append("    implementation(platform(\"")
                    .append(kEsc(gav(d, locked, report)))
                    .append("\"))\n");
        }
        for (Pair pr : order) {
            for (Dependency d : jk.dependencies().of(pr.scope())) {
                if (warnIfUnmappable(d, report)) continue;
                String projectPath = exporting.projectPaths().get(d.module());
                if (projectPath != null && (d.isTestsKind() || d.fixtures())) {
                    report.warning(d.module() + ": tests of a workspace member written as the member itself");
                }
                String notation = projectPath != null
                        ? "project(\"" + kEsc(projectPath) + "\")"
                        : "\"" + kEsc(gav(d, locked, report)) + "\"";
                sb.append("    ")
                        .append(pr.config())
                        .append('(')
                        .append(notation)
                        .append(')')
                        .append(excludeBlock(d.exclusions()))
                        .append('\n');
            }
        }
        // [managed-dependencies] are Gradle's constraints: a version for a module the graph brings in.
        List<Dependency> managed = jk.dependencies().of(Scope.MANAGED);
        if (!managed.isEmpty()) {
            sb.append("    constraints {\n");
            for (Dependency d : managed) {
                if (warnIfUnmappable(d, report)) continue;
                sb.append("        implementation(\"")
                        .append(kEsc(gav(d, locked, report)))
                        .append("\")\n");
                if (!d.exclusions().isEmpty()) {
                    report.warning(d.module() + ": exclusions " + String.join(", ", d.exclusions())
                            + " dropped; add them where the dependency is declared");
                }
            }
            sb.append("    }\n");
        }
        sb.append("}\n");
    }

    /**
     * The exclusions of one dependency as its declaration's block: {@code exclude(group = …, module
     * = …)} per {@code group:artifact}, a {@code *} side left out, and {@code *:*} — the whole
     * subtree — as {@code isTransitive = false}. Empty when the edge prunes nothing.
     */
    static String excludeBlock(List<String> exclusions) {
        if (exclusions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" {\n");
        for (String exclusion : exclusions) {
            int colon = exclusion.indexOf(':');
            String group = colon < 0 ? exclusion : exclusion.substring(0, colon);
            String module = colon < 0 ? "*" : exclusion.substring(colon + 1);
            if ("*".equals(group) && "*".equals(module)) {
                sb.append("        isTransitive = false\n");
                continue;
            }
            List<String> args = new ArrayList<>(2);
            if (!"*".equals(group)) args.add("group = \"" + kEsc(group) + "\"");
            if (!"*".equals(module)) args.add("module = \"" + kEsc(module) + "\"");
            sb.append("        exclude(").append(String.join(", ", args)).append(")\n");
        }
        return sb.append("    }").toString();
    }

    /**
     * jk's {@link Layout#SIMPLE} layout is Mill-like ({@code ./src}, {@code ./test/src},
     * {@code ./resources}, {@code ./test/resources}) where Gradle defaults to {@code src/main/java}.
     * Remap the source sets so the exported build compiles the same files.
     */
    private static void appendSourceSets(StringBuilder sb, Layout layout, boolean kotlin) {
        if (layout != Layout.SIMPLE) return;
        sb.append("\nsourceSets {\n");
        sb.append("    main {\n");
        sb.append("        java.setSrcDirs(listOf(\"src\"))\n");
        if (kotlin) sb.append("        kotlin.setSrcDirs(listOf(\"src\"))\n");
        sb.append("        resources.setSrcDirs(listOf(\"resources\"))\n");
        sb.append("    }\n");
        sb.append("    test {\n");
        sb.append("        java.setSrcDirs(listOf(\"test/src\"))\n");
        if (kotlin) sb.append("        kotlin.setSrcDirs(listOf(\"test/src\"))\n");
        sb.append("        resources.setSrcDirs(listOf(\"test/resources\"))\n");
        sb.append("    }\n");
        sb.append("}\n");
    }

    private static void appendToolchain(StringBuilder sb, Project p) {
        int major = p.jdkMajor() > 0 ? p.jdkMajor() : p.javaRelease();
        if (major <= 0) return;
        sb.append("\njava {\n    toolchain {\n        languageVersion = JavaLanguageVersion.of(")
                .append(major)
                .append(")\n    }\n}\n");
    }

    private static void appendManifest(StringBuilder sb, Map<String, String> manifest) {
        if (manifest.isEmpty()) return;
        sb.append("\ntasks.jar {\n    manifest {\n        attributes(mapOf(\n");
        int i = 0;
        for (Map.Entry<String, String> e : manifest.entrySet()) {
            sb.append("            \"")
                    .append(kEsc(e.getKey()))
                    .append("\" to \"")
                    .append(kEsc(e.getValue()))
                    .append('"');
            sb.append(++i < manifest.size() ? ",\n" : "\n");
        }
        sb.append("        ))\n    }\n}\n");
    }

    private static boolean warnIfUnmappable(Dependency d, ImportReport.Builder report) {
        if (d.isWorkspace()) {
            report.warning(d.workspaceName() + ": no workspace member or shared dependency; dropped");
            return true;
        }
        if (d.isGit()) {
            report.warning(d.module() + ": git source dropped; includeBuild a local checkout");
            return true;
        }
        if (d.isPath()) {
            report.warning(Objects.requireNonNull(d.pathSource()).rawPath()
                    + ": path dependency dropped; includeBuild that directory");
            return true;
        }
        if (d.isFile()) {
            report.warning(d.module() + ": sha256 file dependency dropped");
            return true;
        }
        return false;
    }

    /**
     * {@code group:artifact:version}, plus {@code :classifier} for a classified or tests-kind edge.
     * Lock rows key on the package id ({@code g:a:jar:classifier}); a bare {@code group:artifact}
     * key is read when no row matches.
     */
    private static String gav(Dependency d, Map<String, String> locked, ImportReport.Builder report) {
        String v = locked.get(d.packageKey());
        if (v == null || v.isBlank()) v = locked.get(d.module());
        if (v == null || v.isBlank()) v = extractVersion(d.version(), d.module(), report);
        String classifier = d.isTestsKind() ? PackageId.TESTS_CLASSIFIER : d.classifier();
        return d.module() + ":" + v + (classifier == null || classifier.isBlank() ? "" : ":" + classifier);
    }

    private static void warnDropped(JkBuild jk, ImportReport.Builder report) {
        if (!jk.features().isEmpty()) {
            report.warning("[features] dropped; consider Gradle feature variants");
        }
        if (jk.profiles() != null && !jk.profiles().byName().isEmpty()) {
            report.warning("[profiles] dropped");
        }
        for (Scope s : new Scope[] {Scope.DEV, Scope.TEST_DEV}) {
            if (!jk.dependencies().of(s).isEmpty()) report.warning("[" + s.tomlSection() + "] dropped");
        }
        BuildBlock.War war = jk.build().war();
        if (war != null && (war.name() != null || !BuildBlock.War.DEFAULT_WEBAPP.equals(war.webapp()))) {
            report.warning("[war] name and webapp dropped; the war plugin reads " + BuildBlock.War.DEFAULT_WEBAPP);
        }
    }

    private static String extractVersion(VersionSelector v, String module, ImportReport.Builder report) {
        return switch (v) {
            case VersionSelector.Exact e -> e.version();
            case VersionSelector.Caret c -> {
                report.warning(module + ": ^" + c.version() + " pinned to " + c.version());
                yield c.version();
            }
            case VersionSelector.Tilde t -> {
                report.warning(module + ": ~" + t.version() + " pinned to " + t.version());
                yield t.version();
            }
            case VersionSelector.Range r -> {
                report.warning(module + ": range " + r.raw() + " written as is; check Gradle's syntax");
                yield r.raw();
            }
            case VersionSelector.Latest l -> {
                report.warning(module + ": latest written as latest.release");
                yield "latest.release";
            }
            case VersionSelector.Snapshot s -> {
                // Gradle's own name for the same intent: newest, pre-releases included.
                report.warning(module + ": snapshot written as latest.integration");
                yield "latest.integration";
            }
        };
    }

    /** Escape a Kotlin string literal: {@code \}, {@code "}, the template {@code $}, line breaks and tabs. */
    private static String kEsc(@Nullable String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '$' -> out.append("\\$");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
