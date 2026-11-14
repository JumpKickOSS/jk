// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.ide;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.util.MinimalXml;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Emits VS Code project configuration for the <b>redhat.java</b> language server (Eclipse JDT-LS)
 * plus <b>vscjava.vscode-java-debug</b>.
 *
 * <p>Rather than the fragile "invisible project" {@code java.project.*} settings (single output
 * path, no live cross-module source deps), it generates native <b>Eclipse project metadata</b>
 * {@code .project} / {@code .classpath} / {@code .settings/org.eclipse.jdt.core.prefs} per module
 * which JDT-LS consumes directly and which supports per-module output dirs, live sibling <i>source</i>
 * references, test/main split, and per-module JDK levels. Workspace-wide bits (JDK runtimes, LS
 * vmargs, disabling the Maven/Gradle importers) go in {@code .vscode/settings.json}.
 *
 * <p>JDT-LS compiles into {@code target/jdt/classes/{main,test}} (see {@code BuildLayout#jdtClassesDir})
 * — isolated from jk's own {@code target/classes} so the two incremental compilers never fight.
 */
public final class VscodeIdeGenerator implements IdeGenerator {

    @Override
    public IdeTarget target() {
        return IdeTarget.VSCODE;
    }

    @Override
    public void generate(IdeModel model, IdeOutput out) throws IOException {
        // Eclipse project names are workspace-global and cross-module deps reference them by name
        // reject duplicates rather than emit a silently-broken classpath.
        assertUniqueNames(model);

        for (JdtProjects.Project project : JdtProjects.of(model)) {
            writeModule(out, project);
        }

        Path vscodeDir = model.wsRoot().resolve(".vscode");
        out.file(vscodeDir.resolve("settings.json"), settingsJson(model));
        String launch = launchJson(model);
        if (launch != null) {
            out.file(vscodeDir.resolve("launch.json"), launch);
        }
        out.file(vscodeDir.resolve("extensions.json"), extensionsJson());
        // tasks for jk test / -A / per-suite
        out.file(vscodeDir.resolve("tasks.json"), tasksJson(model));
    }

    // =========================================================================
    // Per-module Eclipse metadata
    // =========================================================================

    private static void writeModule(IdeOutput out, JdtProjects.Project project) throws IOException {
        Path moduleDir = project.dir();
        out.file(moduleDir.resolve(".project"), dotProject(project.name()));
        out.file(moduleDir.resolve(".classpath"), dotClasspath(out, project));
        Path settings = moduleDir.resolve(".settings");
        out.file(settings.resolve("org.eclipse.jdt.core.prefs"), corePrefs(project.compliance()));
    }

    private static String dotProject(String name) {
        StringBuilder sb = xmlHeader();
        sb.append("<projectDescription>\n");
        sb.append("  <name>").append(esc(name)).append("</name>\n");
        sb.append("  <comment></comment>\n");
        sb.append("  <projects></projects>\n");
        sb.append("  <buildSpec>\n");
        sb.append("    <buildCommand>\n");
        sb.append("      <name>org.eclipse.jdt.core.javabuilder</name>\n");
        sb.append("      <arguments></arguments>\n");
        sb.append("    </buildCommand>\n");
        sb.append("  </buildSpec>\n");
        sb.append("  <natures>\n");
        sb.append("    <nature>org.eclipse.jdt.core.javanature</nature>\n");
        sb.append("  </natures>\n");
        sb.append("</projectDescription>\n");
        return sb.toString();
    }

    /** The project's {@code .classpath}: its entries, module-relative inside it, absolute outside. */
    static String dotClasspath(IdeOutput out, JdtProjects.Project project) {
        Path moduleDir = project.dir();
        StringBuilder sb = xmlHeader();
        sb.append("<classpath>\n");
        for (JdtProjects.Entry entry : project.entries()) {
            switch (entry) {
                case JdtProjects.Source src -> {
                    if (src.generated()) {
                        try {
                            out.directory(src.path());
                        } catch (IOException ignored) {
                            // best-effort; JDT tolerates a missing dir with a warning
                        }
                    }
                    sb.append("  <classpathentry kind=\"src\" path=\"")
                            .append(esc(rel(moduleDir, src.path())))
                            .append("\"");
                    sb.append(" output=\"")
                            .append(esc(rel(moduleDir, src.output())))
                            .append("\"");
                    if (src.test()) sb.append(" ").append(testAttr());
                    sb.append("/>\n");
                }
                case JdtProjects.ProjectRef ref -> {
                    sb.append("  <classpathentry combineaccessrules=\"false\" kind=\"src\" path=\"/")
                            .append(esc(ref.name()))
                            .append("\"");
                    if (ref.test()) sb.append(" ").append(testAttr());
                    sb.append("/>\n");
                }
                case JdtProjects.SiblingTests tests ->
                    sb.append("  <classpathentry kind=\"lib\" path=\"")
                            .append(esc(abs(tests.classes())))
                            .append("\" ")
                            .append(testAttr())
                            .append("/>\n");
                case JdtProjects.Container con ->
                    sb.append("  <classpathentry kind=\"con\" path=\"")
                            .append(esc(con.path()))
                            .append("\"/>\n");
                case JdtProjects.Library lib -> {
                    sb.append("  <classpathentry kind=\"lib\" path=\"")
                            .append(esc(abs(lib.path())))
                            .append("\"");
                    Path sources = lib.sources();
                    if (sources != null)
                        sb.append(" sourcepath=\"").append(esc(abs(sources))).append("\"");
                    if (lib.test()) {
                        sb.append(">\n    <attributes>\n      <attribute name=\"test\" value=\"true\"/>\n")
                                .append("    </attributes>\n  </classpathentry>\n");
                    } else {
                        sb.append("/>\n");
                    }
                }
            }
        }
        sb.append("  <classpathentry kind=\"output\" path=\"")
                .append(esc(rel(moduleDir, project.output())))
                .append("\"/>\n");
        sb.append("</classpath>\n");
        return sb.toString();
    }

    private static String corePrefs(String v) {
        return "eclipse.preferences.version=1\n"
                + "org.eclipse.jdt.core.compiler.codegen.targetPlatform=" + v + "\n"
                + "org.eclipse.jdt.core.compiler.compliance=" + v + "\n"
                + "org.eclipse.jdt.core.compiler.release=enabled\n"
                + "org.eclipse.jdt.core.compiler.source=" + v + "\n";
    }

    private static String testAttr() {
        return "test=\"true\"";
    }

    // =========================================================================
    // .vscode/*.json
    // =========================================================================

    private static String settingsJson(IdeModel model) {
        // One runtime per distinct language level, keyed by execution-environment name.
        Map<Integer, SdkRef> byLevel = new LinkedHashMap<>();
        for (SdkRef r : model.sdkRefs().values()) byLevel.putIfAbsent(r.languageLevel(), r);
        byLevel.putIfAbsent(model.defaultSdk().languageLevel(), model.defaultSdk());
        int defaultLevel = model.defaultSdk().languageLevel();

        StringBuilder rt = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Integer, SdkRef> e : byLevel.entrySet()) {
            if (!first) rt.append(",\n");
            first = false;
            rt.append("    {\n");
            rt.append("      \"name\": \"").append(eeName(e.getKey())).append("\",\n");
            rt.append("      \"path\": \"")
                    .append(jsonEsc(abs(e.getValue().javaHome())))
                    .append("\"");
            if (e.getKey() == defaultLevel) rt.append(",\n      \"default\": true");
            rt.append("\n    }");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"editor.semanticHighlighting.enabled\": true,\n");
        // The JDK that RUNS the language server must be 21+; prefer the project default, else the
        // highest available >= 21. Omit the key (fall back to JAVA_HOME / bundled JRE) if none.
        Path lsHome = languageServerHome(byLevel, defaultLevel);
        if (lsHome != null) {
            sb.append("  \"java.jdt.ls.java.home\": \"")
                    .append(jsonEsc(abs(lsHome)))
                    .append("\",\n");
        }
        sb.append("  \"java.configuration.runtimes\": [\n").append(rt).append("\n  ],\n");
        sb.append("  \"java.import.maven.enabled\": false,\n");
        sb.append("  \"java.import.gradle.enabled\": false,\n");
        sb.append("  \"java.server.launchMode\": \"Standard\",\n");
        sb.append("  \"java.autobuild.enabled\": true,\n");
        sb.append("  \"java.completion.guessMethodArguments\": \"insertBestGuessedArguments\",\n");
        sb.append("  \"java.inlayHints.parameterNames.enabled\": \"all\",\n");
        sb.append("  \"java.compile.nullAnalysis.mode\": \"automatic\",\n");
        sb.append("  \"java.jdt.ls.vmargs\": \"-XX:+UseParallelGC -XX:GCTimeRatio=4 "
                + "-XX:AdaptiveSizePolicyWeight=90 -Dsun.zip.disableMemoryMapping=true -Xmx2G -Xms100m\"\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static @Nullable Path languageServerHome(Map<Integer, SdkRef> byLevel, int defaultLevel) {
        SdkRef def = byLevel.get(defaultLevel);
        if (def != null && defaultLevel >= 21) return def.javaHome();
        SdkRef best = null;
        for (Map.Entry<Integer, SdkRef> e : byLevel.entrySet()) {
            if (e.getKey() >= 21 && (best == null || e.getKey() > best.languageLevel())) best = e.getValue();
        }
        return best != null ? best.javaHome() : null;
    }

    private static @Nullable String launchJson(IdeModel model) {
        List<String> configs = new ArrayList<>();
        Map<Path, IdeModule> targets = model.modules().isEmpty() ? model.allModules() : model.modules();
        for (IdeModule module : targets.values()) {
            String main = module.mainClass();
            if (main == null) continue;
            String name = module.name();
            configs.add("    {\n"
                    + "      \"type\": \"java\",\n"
                    + "      \"name\": \"" + jsonEsc(name) + "\",\n"
                    + "      \"request\": \"launch\",\n"
                    + "      \"mainClass\": \"" + jsonEsc(main) + "\",\n"
                    + "      \"projectName\": \"" + jsonEsc(name) + "\"\n"
                    + "    }");
        }
        if (configs.isEmpty()) return null;
        return "{\n  \"version\": \"0.2.0\",\n  \"configurations\": [\n" + String.join(",\n", configs) + "\n  ]\n}\n";
    }

    private static String extensionsJson() {
        return "{\n  \"recommendations\": [\n"
                + "    \"redhat.java\",\n"
                + "    \"vscjava.vscode-java-debug\"\n"
                + "  ]\n}\n";
    }

    /**shell tasks for default suite, --all, and each extra suite. */
    static String tasksJson(IdeModel model) {
        List<String> tasks = new ArrayList<>();
        tasks.add(taskEntry("jk: test", "jk test"));
        tasks.add(taskEntry("jk: test all", "jk test -A"));
        LinkedHashSet<String> extra = new LinkedHashSet<>();
        for (Path mod : model.allModules().keySet()) {
            for (String suite : IdeSourceRoots.suites(mod)) {
                if (!TestSuites.DEFAULT.equals(suite)) extra.add(suite);
            }
        }
        for (String suite : extra) {
            tasks.add(taskEntry("jk: test · " + suite, "jk test -s " + suite));
        }
        return "{\n  \"version\": \"2.0.0\",\n  \"tasks\": [\n" + String.join(",\n", tasks) + "\n  ]\n}\n";
    }

    private static String taskEntry(String label, String command) {
        return "    {\n"
                + "      \"label\": \"" + jsonEsc(label) + "\",\n"
                + "      \"type\": \"shell\",\n"
                + "      \"command\": \"" + jsonEsc(command) + "\",\n"
                + "      \"group\": \"test\",\n"
                + "      \"presentation\": { \"reveal\": \"always\", \"panel\": \"shared\" },\n"
                + "      \"problemMatcher\": []\n"
                + "    }";
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void assertUniqueNames(IdeModel model) {
        Set<String> seen = new LinkedHashSet<>();
        for (IdeModule module : model.allModules().values()) {
            String name = module.name();
            if (!seen.add(name)) {
                throw new IdeException(
                        2, "duplicate module name '" + name + "' — Eclipse project names must be unique");
            }
        }
    }

    /** Eclipse execution-environment name for a Java level (e.g. {@code JavaSE-25}, {@code JavaSE-1.8}). */
    private static String eeName(int level) {
        return "JavaSE-" + eeLevelString(level);
    }

    private static String eeLevelString(int level) {
        int l = Math.min(level, JdtProjects.MAX_KNOWN_EE);
        return l <= 8 ? "1." + l : String.valueOf(l);
    }

    /** Module-relative, forward-slash path. */
    private static String rel(Path moduleDir, Path target) {
        return moduleDir.relativize(target).toString().replace('\\', '/');
    }

    /** Absolute, forward-slash path. */
    private static String abs(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static StringBuilder xmlHeader() {
        return new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    }

    private static String esc(String s) {
        return MinimalXml.escapeAttr(s);
    }

    private static String jsonEsc(String s) {
        // Delegate to the canonical Jsonl codec (also escapes control chars, which the old
        // two-replace version dropped) and strip its surrounding quotes — call sites add their own.
        if (s == null) return "";
        String quoted = Jsonl.quote(s);
        return quoted.substring(1, quoted.length() - 1);
    }
}
