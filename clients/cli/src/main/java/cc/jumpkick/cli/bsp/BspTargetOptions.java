// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.wire.protocol.IdeWireModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The per-target answers a Scala-aware BSP client (Metals) asks for beside the build targets:
 * {@code buildTarget/scalacOptions} and {@code buildTarget/javacOptions} — the compiler
 * arguments jk's Zinc session passes, the compile classpath and the class directory —
 * {@code buildTarget/dependencySources}, and the Scala main and test class listings. Every
 * answer is built from the engine's {@link IdeWireModel}; the target ids are the ones
 * {@code workspace/buildTargets} handed out.
 */
final class BspTargetOptions {

    /** The {@code dataKind} and organisation Metals reads a Scala target by. */
    static final String SCALA_DATA_KIND = "scala";

    static final String SCALA_ORGANIZATION = "org.scala-lang";

    private BspTargetOptions() {}

    /** The {@code ScalaBuildTarget} data of module {@code i}, or {@code null} for a module that compiles no Scala. */
    static @Nullable String scalaTargetData(IdeWireModel model, int i) {
        String version = model.scalaVersionOf(i);
        if (version == null) return null;
        List<String> jars = new ArrayList<>();
        for (Path jar : rowsOf(model.scalaJars(), i)) jars.add(uri(jar));
        JsonFields data = JsonFields.object()
                .string("scalaOrganization", SCALA_ORGANIZATION)
                .string("scalaVersion", version)
                .string("scalaBinaryVersion", binaryVersion(version))
                .number("platform", 1)
                .array("jars", jars);
        String jvm = jvmTargetData(model, i);
        if (jvm != null) data.token("jvmBuildTarget", jvm);
        return data.finish();
    }

    /** The {@code JvmBuildTarget} data of module {@code i}: its JDK home and version, when the model names one. */
    static @Nullable String jvmTargetData(IdeWireModel model, int i) {
        String home = i < model.sdkHomes().size() ? model.sdkHomes().get(i) : "";
        if (home == null || home.isBlank()) return null;
        String version = i < model.sdkVersions().size() ? model.sdkVersions().get(i) : "";
        return JsonFields.object()
                .string("javaHome", uri(Path.of(home)))
                .string("javaVersion", version == null ? "" : version)
                .finish();
    }

    /** {@code 3} for Scala 3, else the first two segments ({@code 2.13}). */
    static String binaryVersion(String version) {
        if (version.startsWith("3.") || version.equals("3")) return "3";
        int first = version.indexOf('.');
        int second = first < 0 ? -1 : version.indexOf('.', first + 1);
        return second < 0 ? version : version.substring(0, second);
    }

    /**
     * {@code ScalacOptionsItem}: the arguments jk's Zinc session passes scalac for the module's
     * Java release, the compile classpath and the class directory.
     */
    static String scalacOptionsItem(String tid, IdeWireModel model, int i) {
        List<String> options = new ArrayList<>();
        String release = releaseOf(model, i);
        if (!release.isEmpty()) {
            options.add("-java-output-version");
            options.add(release);
        }
        return optionsItem(tid, model, i, options);
    }

    /** {@code JavacOptionsItem}: {@code --release} for the module's Java level, the classpath and the class directory. */
    static String javacOptionsItem(String tid, IdeWireModel model, int i) {
        List<String> options = new ArrayList<>();
        String release = releaseOf(model, i);
        if (!release.isEmpty()) {
            options.add("--release");
            options.add(release);
        }
        return optionsItem(tid, model, i, options);
    }

    private static String optionsItem(String tid, IdeWireModel model, int i, List<String> options) {
        return BspServer.itemFor(tid)
                .array("options", options)
                .array("classpath", classpath(model, i))
                .string("classDirectory", uri(Path.of(model.classesDirs().get(i))))
                .finish();
    }

    /**
     * The module's compile classpath as URIs: every resolved library jar, the Scala library jars
     * of its compiler closure, and the main classes of the siblings it depends on.
     */
    static List<String> classpath(IdeWireModel model, int i) {
        Set<String> out = new LinkedHashSet<>();
        for (Path jar : rowsOf(model.scalaJars(), i)) {
            String name = jar.getFileName().toString();
            if (name.startsWith("scala-library") || name.startsWith("scala3-library_3")) out.add(uri(jar));
        }
        for (String row : model.siblingRefs()) {
            String[] parts = row.split("\\|", 3);
            if (parts.length < 2 || Integer.parseInt(parts[0]) != i) continue;
            int sibling = model.names().indexOf(parts[1]);
            if (sibling >= 0 && sibling < model.classesDirs().size()) {
                out.add(uri(Path.of(model.classesDirs().get(sibling))));
            }
        }
        for (String jar : model.libJars()) {
            if (jar == null || jar.isBlank()) continue;
            String path = jar.contains("|") ? jar.substring(jar.lastIndexOf('|') + 1) : jar;
            out.add(uri(Path.of(path)));
        }
        return new ArrayList<>(out);
    }

    /** {@code DependencySourcesItem}: the sources jars the model holds for the resolved libraries. */
    static String dependencySourcesItem(String tid, IdeWireModel model) {
        List<String> sources = new ArrayList<>();
        for (String src : model.libSources()) {
            if (src != null && !src.isBlank()) sources.add(uri(Path.of(src)));
        }
        return BspServer.itemFor(tid).array("sources", sources).finish();
    }

    /** {@code ScalaMainClassesItem}: the module's main class when it declares one. */
    static String scalaMainClassesItem(String tid, IdeWireModel model, int i) {
        List<String> classes = new ArrayList<>();
        String main = i < model.mainClasses().size() ? model.mainClasses().get(i) : "";
        if (main != null && !main.isBlank()) {
            classes.add(JsonFields.object()
                    .string("class", main)
                    .array("arguments", List.of())
                    .array("jvmOptions", List.of())
                    .array("environmentVariables", List.of())
                    .finish());
        }
        return BspServer.itemFor(tid)
                .token("classes", "[" + String.join(",", classes) + "]")
                .finish();
    }

    /** {@code ScalaTestClassesItem} with no classes: test discovery is the engine's, run through {@code buildTarget/test}. */
    static String scalaTestClassesItem(String tid) {
        return BspServer.itemFor(tid).array("classes", List.of()).finish();
    }

    /** The module's Java release as the compiler flag spells it; {@code ""} when none is known. */
    private static String releaseOf(IdeWireModel model, int i) {
        String r = i < model.javaReleases().size() ? model.javaReleases().get(i) : "";
        if (r == null || r.isBlank()) return "";
        try {
            return Integer.parseInt(r.trim()) > 0 ? r.trim() : "";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /** The paths of the {@code i|path} rows that belong to module {@code i}. */
    private static List<Path> rowsOf(List<String> rows, int i) {
        List<Path> out = new ArrayList<>();
        for (String row : rows) {
            int bar = row.indexOf('|');
            if (bar > 0 && Integer.parseInt(row.substring(0, bar)) == i) out.add(Path.of(row.substring(bar + 1)));
        }
        return out;
    }

    private static String uri(Path p) {
        return p.toAbsolutePath().normalize().toUri().toString();
    }
}
