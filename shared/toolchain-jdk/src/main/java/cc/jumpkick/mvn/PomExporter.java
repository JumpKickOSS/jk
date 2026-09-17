// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Layout;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.pom.PomXml;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Renders a {@link JkBuild} as a Maven {@code pom.xml} (companion to {@code PomImporter}).
 * Platform → dependencyManagement import; processor → annotationProcessorPaths; workspace root →
 * packaging pom + modules. Features/profiles stripped; floating selectors collapse with a warning.
 */
public final class PomExporter {

    public record Result(String xml, ImportReport report) {}

    private PomExporter() {}

    /** Export with traditional (Maven) source dirs and no locked versions (collapses selectors). */
    public static Result export(JkBuild jkBuild) {
        return export(jkBuild, Layout.TRADITIONAL, Map.of());
    }

    /** Export with traditional (Maven) source dirs. */
    public static Result export(JkBuild jkBuild, Map<String, String> locked) {
        return export(jkBuild, Layout.TRADITIONAL, locked);
    }

    /**
     * Export as {@code pom.xml}. {@code locked} module→version uses exact pins when present;
     * otherwise selectors collapse with a warning. {@code SIMPLE} emits flat source dirs.
     */
    public static Result export(JkBuild jkBuild, Layout layout, Map<String, String> locked) {
        if (locked == null) locked = Map.of();
        if (layout == null) layout = Layout.TRADITIONAL;
        ImportReport.Builder report = ImportReport.builder();
        StringBuilder sb = new StringBuilder(1024);
        PomXml.appendPreamble(sb);

        Project p = jkBuild.project();
        appendCoords(sb, p, jkBuild.isWorkspaceRoot());
        appendProperties(sb, p);
        appendModules(sb, jkBuild);
        appendRepositories(sb, jkBuild.repositories());
        appendDependencyManagement(
                sb,
                jkBuild.dependencies().of(Scope.PLATFORM),
                jkBuild.dependencies().of(Scope.MANAGED),
                locked,
                report);
        appendDependencies(sb, jkBuild.dependencies(), locked, report);
        appendBuild(sb, jkBuild, layout, locked, report);
        warnAboutDroppedConcerns(jkBuild, report);

        sb.append("</project>\n");
        return new Result(sb.toString(), report.build());
    }

    private static void appendCoords(StringBuilder sb, Project p, boolean workspaceRoot) {
        sb.append("  <groupId>").append(PomXml.escape(p.group())).append("</groupId>\n");
        sb.append("  <artifactId>").append(PomXml.escape(p.name())).append("</artifactId>\n");
        sb.append("  <version>").append(PomXml.escape(p.version())).append("</version>\n");
        sb.append("  <packaging>").append(workspaceRoot ? "pom" : "jar").append("</packaging>\n");
        if (p.description() != null) {
            sb.append("  <description>").append(PomXml.escape(p.description())).append("</description>\n");
        }
    }

    private static void appendProperties(StringBuilder sb, Project p) {
        sb.append('\n');
        sb.append("  <properties>\n");
        sb.append("    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n");
        sb.append("    <maven.compiler.release>").append(p.javaRelease()).append("</maven.compiler.release>\n");
        sb.append("  </properties>\n");
    }

    private static void appendModules(StringBuilder sb, JkBuild jkBuild) {
        if (!jkBuild.isWorkspaceRoot()) return;
        sb.append('\n');
        sb.append("  <modules>\n");
        for (String module : Objects.requireNonNull(jkBuild.workspace()).modules()) {
            sb.append("    <module>").append(PomXml.escape(module)).append("</module>\n");
        }
        sb.append("  </modules>\n");
    }

    private static void appendRepositories(StringBuilder sb, List<RepositorySpec> repos) {
        if (repos.isEmpty()) return;
        sb.append('\n');
        sb.append("  <repositories>\n");
        for (RepositorySpec r : repos) {
            sb.append("    <repository>\n");
            sb.append("      <id>").append(PomXml.escape(r.name())).append("</id>\n");
            sb.append("      <url>").append(PomXml.escape(r.url().toString())).append("</url>\n");
            sb.append("    </repository>\n");
        }
        sb.append("  </repositories>\n");
    }

    /**
     * BOM imports, then one plain managed {@code <dependency>} per {@code [managed-dependencies]}
     * entry, its exclusions included.
     */
    private static void appendDependencyManagement(
            StringBuilder sb,
            List<Dependency> platforms,
            List<Dependency> managed,
            Map<String, String> locked,
            ImportReport.Builder report) {
        if (platforms.isEmpty() && managed.isEmpty()) return;
        sb.append('\n'); // export POM separates sections with a blank line (publish POM does not)
        List<Dependency> mappable = new ArrayList<>(managed.size());
        for (Dependency d : managed) {
            if (!warnIfUnmappable(d, report)) mappable.add(d);
        }
        PomXml.appendManagedDependencies(sb, platforms, d -> resolveVersion(d, locked, report), mappable);
    }

    private static void appendDependencies(
            StringBuilder sb, JkBuild.Dependencies deps, Map<String, String> locked, ImportReport.Builder report) {
        // Maven emits compile / runtime / provided / test under <dependencies>;
        // platform and processor are handled elsewhere.
        Scope[] order = {Scope.MAIN, Scope.RUNTIME, Scope.PROVIDED, Scope.TEST};
        boolean any = false;
        for (Scope s : order) {
            if (!deps.of(s).isEmpty()) {
                any = true;
                break;
            }
        }
        if (!any) return;

        sb.append('\n');
        sb.append("  <dependencies>\n");
        for (Scope s : order) {
            for (Dependency d : deps.of(s)) {
                appendDependency(sb, d, PomXml.mavenScope(s), locked, report);
            }
        }
        sb.append("  </dependencies>\n");
    }

    private static void appendDependency(
            StringBuilder sb,
            Dependency d,
            @Nullable String mavenScope,
            Map<String, String> locked,
            ImportReport.Builder report) {
        if (warnIfUnmappable(d, report)) return;
        PomXml.appendDependency(sb, d, resolveVersion(d, locked, report), mavenScope);
    }

    /** Git / content-addressed deps have no Maven equivalent — warn and skip. */
    private static boolean warnIfUnmappable(Dependency d, ImportReport.Builder report) {
        if (d.isGit()) {
            report.warning("dependency `"
                    + d.module()
                    + "` is git-sourced; Maven has no git-source"
                    + " equivalent — dropped. Build & install that project to your local repo"
                    + " (`mvn install`, or `jk install`) so it resolves by coordinate, then add it"
                    + " as a normal <dependency>.");
            return true;
        }
        if (d.isPath()) {
            report.warning("dependency `"
                    + Objects.requireNonNull(d.pathSource()).rawPath()
                    + "` is a local path dependency; Maven has no path-source"
                    + " equivalent — dropped. Build & install that project to your local repo"
                    + " (`mvn install`, or `jk install`) so it resolves by coordinate, then add it"
                    + " as a normal <dependency>.");
            return true;
        }
        if (d.isFile()) {
            report.warning("dependency `"
                    + d.module()
                    + "` is content-addressed (sha256); no Maven"
                    + " equivalent — dropped.");
            return true;
        }
        return false;
    }

    /** Locked exact version when known, else the collapsed selector (with a warning). */
    private static String resolveVersion(Dependency d, Map<String, String> locked, ImportReport.Builder report) {
        // Lock rows key on the full package id (g:a:type:classifier, e65323f6) — packageKey()
        // hits both the plain jar (g:a:jar:) and a tests-kind dep (g:a:test-jar:tests). Bare GA
        // stays as a fallback for hand-built maps.
        String fromLock = locked.get(d.packageKey());
        if (fromLock == null || fromLock.isBlank()) fromLock = locked.get(d.module());
        if (fromLock != null && !fromLock.isBlank()) return fromLock;
        return extractVersion(d.version(), d.module(), report);
    }

    // Plugin coordinates baked into exported POMs — bump as upstreams release.
    private static final String TOOLCHAINS_PLUGIN = "4.5.0"; // org.mvnsearch:toolchains-maven-plugin (foojay)
    private static final String SHADE_PLUGIN = "3.6.0"; // org.apache.maven.plugins:maven-shade-plugin
    private static final String NATIVE_PLUGIN = "0.10.3"; // org.graalvm.buildtools:native-maven-plugin
    private static final String JAR_PLUGIN = "3.4.1"; // org.apache.maven.plugins:maven-jar-plugin

    private static void appendBuild(
            StringBuilder sb, JkBuild jkBuild, Layout layout, Map<String, String> locked, ImportReport.Builder report) {
        Project p = jkBuild.project();
        List<Dependency> processors = jkBuild.dependencies().of(Scope.PROCESSOR);
        List<Dependency> testProcessors = jkBuild.dependencies().of(Scope.TEST_PROCESSOR);
        boolean anyProcessor = !processors.isEmpty() || !testProcessors.isEmpty();
        boolean kotlin = p.kotlin() != null;
        boolean toolchain = p.jdk() != null && !p.jdk().isBlank();
        boolean assembly = jkBuild.assembly();
        boolean nativeImg = jkBuild.nativeMode() == JkBuild.NativeMode.ALWAYS;
        boolean jarManifest = jkBuild.mainClass() != null || !jkBuild.manifest().isEmpty();
        boolean anyPlugin = anyProcessor || kotlin || toolchain || assembly || nativeImg || jarManifest;
        boolean simple = layout == Layout.SIMPLE;
        if (!anyPlugin && !simple) return;

        sb.append('\n').append("  <build>\n");
        // jk's simple Mill-like layout (./src + ./test/src) → Maven's source/test dirs.
        if (simple) {
            sb.append("    <sourceDirectory>src</sourceDirectory>\n");
            sb.append("    <testSourceDirectory>test/src</testSourceDirectory>\n");
            sb.append("    <resources><resource><directory>resources</directory></resource></resources>\n");
            sb.append("    <testResources><testResource><directory>test/resources</directory>")
                    .append("</testResource></testResources>\n");
        }
        if (anyPlugin) {
            sb.append("    <plugins>\n");
            if (kotlin) appendKotlinPlugin(sb, p, report);
            if (anyProcessor) {
                appendCompilerProcessorPlugin(sb, processors, testProcessors, p.javaRelease(), locked, report);
            }
            if (toolchain) appendToolchainsPlugin(sb, p);
            if (jarManifest) appendJarPlugin(sb, jkBuild.mainClass(), jkBuild.manifest());
            // Map jk assemble packaging onto maven-shade-plugin (export only).
            if (assembly) appendShadePlugin(sb);
            if (nativeImg) appendNativePlugin(sb, jkBuild);
            sb.append("    </plugins>\n");
        }
        sb.append("  </build>\n");
    }

    private static void appendKotlinPlugin(StringBuilder sb, Project p, ImportReport.Builder report) {
        String ver = extractVersion(Objects.requireNonNull(p.kotlin()), "kotlin", report);
        sb.append("      <plugin>\n");
        sb.append("        <groupId>org.jetbrains.kotlin</groupId>\n");
        sb.append("        <artifactId>kotlin-maven-plugin</artifactId>\n");
        sb.append("        <version>").append(PomXml.escape(ver)).append("</version>\n");
        sb.append("        <executions>\n");
        sb.append("          <execution><id>compile</id><phase>compile</phase>")
                .append("<goals><goal>compile</goal></goals></execution>\n");
        sb.append("          <execution><id>test-compile</id><phase>test-compile</phase>")
                .append("<goals><goal>test-compile</goal></goals></execution>\n");
        sb.append("        </executions>\n");
        sb.append("      </plugin>\n");
    }

    /**
     * {@code [processor-dependencies]} as the plugin's own {@code <annotationProcessorPaths>}, which
     * reach both compiles; {@code [test-processor-dependencies]} as a {@code testCompile} execution
     * whose paths replace the plugin's with the shared list plus its own, so compile-test alone
     * runs them.
     */
    private static void appendCompilerProcessorPlugin(
            StringBuilder sb,
            List<Dependency> processors,
            List<Dependency> testProcessors,
            int release,
            Map<String, String> locked,
            ImportReport.Builder report) {
        sb.append("      <plugin>\n");
        sb.append("        <groupId>org.apache.maven.plugins</groupId>\n");
        sb.append("        <artifactId>maven-compiler-plugin</artifactId>\n");
        sb.append("        <configuration>\n");
        sb.append("          <release>").append(release).append("</release>\n");
        if (!processors.isEmpty()) appendProcessorPaths(sb, processors, "          ", false, locked, report);
        sb.append("        </configuration>\n");
        if (!testProcessors.isEmpty()) {
            List<Dependency> all = new ArrayList<>(processors);
            all.addAll(testProcessors);
            sb.append("        <executions>\n");
            sb.append("          <execution>\n");
            sb.append("            <id>default-testCompile</id>\n");
            sb.append("            <goals><goal>testCompile</goal></goals>\n");
            sb.append("            <configuration>\n");
            appendProcessorPaths(sb, all, "              ", true, locked, report);
            sb.append("            </configuration>\n");
            sb.append("          </execution>\n");
            sb.append("        </executions>\n");
        }
        sb.append("      </plugin>\n");
    }

    /** One {@code <path>} per processor; {@code override} makes the list replace the plugin's own. */
    private static void appendProcessorPaths(
            StringBuilder sb,
            List<Dependency> processors,
            String indent,
            boolean override,
            Map<String, String> locked,
            ImportReport.Builder report) {
        sb.append(indent)
                .append(
                        override
                                ? "<annotationProcessorPaths combine.self=\"override\">"
                                : "<annotationProcessorPaths>")
                .append('\n');
        for (Dependency d : processors) {
            sb.append(indent).append("  <path>\n");
            sb.append(indent)
                    .append("    <groupId>")
                    .append(PomXml.escape(d.group()))
                    .append("</groupId>\n");
            sb.append(indent)
                    .append("    <artifactId>")
                    .append(PomXml.escape(d.name()))
                    .append("</artifactId>\n");
            sb.append(indent)
                    .append("    <version>")
                    .append(PomXml.escape(resolveVersion(d, locked, report)))
                    .append("</version>\n");
            sb.append(indent).append("  </path>\n");
        }
        sb.append(indent).append("</annotationProcessorPaths>\n");
    }

    /**
     * Foojay-backed toolchains plugin — auto-downloads the project's JDK (the Maven analog of
     * Gradle's foojay-resolver), so `project.jdk` reproduces jk's JDK auto-provisioning rather than
     * requiring a hand-edited toolchains.xml.
     */
    private static void appendToolchainsPlugin(StringBuilder sb, Project p) {
        int major = p.jdkMajor();
        sb.append("      <plugin>\n");
        sb.append("        <groupId>org.mvnsearch</groupId>\n");
        sb.append("        <artifactId>toolchains-maven-plugin</artifactId>\n");
        sb.append("        <version>").append(TOOLCHAINS_PLUGIN).append("</version>\n");
        sb.append("        <executions><execution><goals><goal>toolchain</goal></goals></execution></executions>\n");
        sb.append("        <configuration>\n          <toolchains>\n            <jdk>\n");
        sb.append("              <version>")
                .append(major > 0 ? major : p.javaRelease())
                .append("</version>\n");
        sb.append("            </jdk>\n          </toolchains>\n        </configuration>\n");
        sb.append("      </plugin>\n");
    }

    private static void appendJarPlugin(StringBuilder sb, @Nullable String mainClass, Map<String, String> manifest) {
        sb.append("      <plugin>\n");
        sb.append("        <groupId>org.apache.maven.plugins</groupId>\n");
        sb.append("        <artifactId>maven-jar-plugin</artifactId>\n");
        sb.append("        <version>").append(JAR_PLUGIN).append("</version>\n");
        sb.append("        <configuration>\n          <archive>\n            <manifest>\n");
        if (mainClass != null) {
            sb.append("              <mainClass>")
                    .append(PomXml.escape(mainClass))
                    .append("</mainClass>\n");
        }
        if (!manifest.isEmpty()) {
            sb.append("            </manifest>\n            <manifestEntries>\n");
            for (Map.Entry<String, String> e : manifest.entrySet()) {
                sb.append("              <")
                        .append(PomXml.escape(e.getKey()))
                        .append('>')
                        .append(PomXml.escape(e.getValue()))
                        .append("</")
                        .append(PomXml.escape(e.getKey()))
                        .append(">\n");
            }
            sb.append("            </manifestEntries>\n");
        } else {
            sb.append("            </manifest>\n");
        }
        sb.append("          </archive>\n        </configuration>\n");
        sb.append("      </plugin>\n");
    }

    private static void appendShadePlugin(StringBuilder sb) {
        sb.append("      <plugin>\n");
        sb.append("        <groupId>org.apache.maven.plugins</groupId>\n");
        sb.append("        <artifactId>maven-shade-plugin</artifactId>\n");
        sb.append("        <version>").append(SHADE_PLUGIN).append("</version>\n");
        sb.append("        <executions><execution><phase>package</phase>")
                .append("<goals><goal>shade</goal></goals></execution></executions>\n");
        sb.append("      </plugin>\n");
    }

    private static void appendNativePlugin(StringBuilder sb, JkBuild jkBuild) {
        String main =
                jkBuild.nativeConfigOpt().map(JkBuild.NativeConfig::mainClass).orElseGet(jkBuild::mainClass);
        sb.append("      <plugin>\n");
        sb.append("        <groupId>org.graalvm.buildtools</groupId>\n");
        sb.append("        <artifactId>native-maven-plugin</artifactId>\n");
        sb.append("        <version>").append(NATIVE_PLUGIN).append("</version>\n");
        sb.append("        <extensions>true</extensions>\n");
        if (main != null && !main.isBlank()) {
            sb.append("        <configuration><mainClass>")
                    .append(PomXml.escape(main))
                    .append("</mainClass></configuration>\n");
        }
        sb.append("      </plugin>\n");
    }

    private static void warnAboutDroppedConcerns(JkBuild jkBuild, ImportReport.Builder report) {
        if (!jkBuild.features().isEmpty()) {
            report.warning(
                    "`features` block dropped — features are jk-only consumer-side" + " selectors; not a POM concept.");
        }
        if (jkBuild.profiles() != null && !jkBuild.profiles().byName().isEmpty()) {
            report.warning("`profiles` block dropped — jk profiles change javac/jvm args at the"
                    + " consumer side, which doesn't translate to Maven profiles.");
        }
    }

    /**
     * Extract a Maven-compatible version string from a {@link VersionSelector}. Maven publishes exact
     * pins; non-exact selectors collapse to their version with a fidelity warning.
     */
    private static String extractVersion(VersionSelector v, String module, ImportReport.Builder report) {
        return switch (v) {
            case VersionSelector.Exact e -> e.version();
            case VersionSelector.Caret c -> {
                report.warning("dependency `"
                        + module
                        + "` was declared as `^"
                        + c.version()
                        + "` (caret); pinned to `"
                        + c.version()
                        + "` in the exported POM."
                        + " Consumers using Maven get exact-match semantics.");
                yield c.version();
            }
            case VersionSelector.Tilde t -> {
                report.warning("dependency `"
                        + module
                        + "` was declared as `~"
                        + t.version()
                        + "` (tilde); pinned to `"
                        + t.version()
                        + "` in the exported POM.");
                yield t.version();
            }
            case VersionSelector.Range r -> {
                // jk range expressions usually look enough like Maven ranges to pass through,
                // but emit a warning so the user reviews.
                report.warning("dependency `"
                        + module
                        + "` uses range `"
                        + r.raw()
                        + "`; emitted verbatim — Maven range syntax differs in edge cases.");
                yield r.raw();
            }
            case VersionSelector.Latest l -> {
                report.warning("dependency `"
                        + module
                        + "` uses `latest`; emitted as `LATEST` —"
                        + " note that Maven 3+ has deprecated LATEST/RELEASE in <version>.");
                yield "LATEST";
            }
            case VersionSelector.Snapshot sn -> {
                report.warning("dependency `"
                        + module
                        + "` uses `snapshot`; emitted as `LATEST` —"
                        + " note that Maven 3+ has deprecated LATEST/RELEASE in <version>.");
                yield "LATEST";
            }
        };
    }
}
