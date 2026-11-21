// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import cc.jumpkick.ide.IdeSourceRoots;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.wire.protocol.IdeWireModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code workspace/buildTargets} answer: one target per workspace module, one test target per
 * suite beside a module's default one, and the target identifiers both are named by.
 */
final class BspTargets {

    private BspTargets() {}

    /**
     * Every target of the build whose root is {@code projectDir}: one per module of {@code model}, or
     * one for the root itself, displayed as {@code rootCoord}, when the model lists no modules.
     */
    static String json(@Nullable IdeWireModel model, Path projectDir, @Nullable String rootCoord) {
        String rootUri = BspServer.pathUri(projectDir);
        List<String> targets = new ArrayList<>();
        if (model != null && model.moduleDirs() != null && !model.moduleDirs().isEmpty()) {
            List<String> dirs = model.moduleDirs();
            List<String> names = model.names() != null ? model.names() : List.of();
            List<String> mains = model.mainClasses() != null ? model.mainClasses() : List.of();
            for (int i = 0; i < dirs.size(); i++) {
                String name = i < names.size()
                        ? names.get(i)
                        : Path.of(dirs.get(i)).getFileName().toString();
                String id = rootUri + "#" + name;
                boolean canRun = i < mains.size()
                        && mains.get(i) != null
                        && !mains.get(i).isBlank();
                targets.add(targetJson(id, name, BspServer.pathUri(Path.of(dirs.get(i))), canRun, model, i));
                addSuiteTargets(targets, id, name, Path.of(dirs.get(i)));
            }
        } else {
            String display = rootCoord != null && !rootCoord.isBlank() ? rootCoord : "root";
            boolean canRun = model != null
                    && model.mainClasses() != null
                    && !model.mainClasses().isEmpty()
                    && model.mainClasses().getFirst() != null
                    && !model.mainClasses().getFirst().isBlank();
            targets.add(targetJson(rootUri + "#root", display, rootUri, canRun, model, 0));
            addSuiteTargets(targets, rootUri + "#root", display, projectDir);
        }
        return JsonFields.object().token("targets", BspServer.arrayOf(targets)).finish();
    }

    /** What separates a module's target id from one of its suites: {@code <module id>::<suite>}. */
    static final String SUITE_SEPARATOR = "::";

    /** One test target per suite of {@code moduleDir} beside its default suite, directory or class pattern. */
    private static void addSuiteTargets(List<String> targets, String moduleId, String display, Path moduleDir) {
        for (String suite : IdeSourceRoots.suites(moduleDir)) {
            if (!TestSuites.DEFAULT.equals(suite)) targets.add(suiteTargetJson(moduleId, display, suite));
        }
    }

    /**
     * A test target for {@code suite} of the module {@code moduleId} names: no sources and no
     * languages of its own (the module's target owns them), so testing it is the one thing it does,
     * {@code jk test -s <suite>} for that module.
     */
    static String suiteTargetJson(String moduleId, String display, String suite) {
        return JsonFields.object()
                .token("id", BspServer.uriJson(moduleId + SUITE_SEPARATOR + suite))
                .string("displayName", display + " · " + suite)
                .array("tags", List.of("test"))
                .array("languageIds", List.of())
                .token("dependencies", BspServer.arrayOf(List.of(BspServer.uriJson(moduleId))))
                .token(
                        "capabilities",
                        JsonFields.object()
                                .bool("canCompile", false)
                                .bool("canTest", true)
                                .bool("canRun", false)
                                .finish())
                .finish();
    }

    /** The suite a target id names after {@link #SUITE_SEPARATOR}, or {@code null} for a module's own target. */
    static @Nullable String suiteOf(String targetUri) {
        int hash = targetUri.indexOf('#');
        int at = targetUri.indexOf(SUITE_SEPARATOR, Math.max(0, hash));
        return hash < 0 || at < 0 ? null : targetUri.substring(at + SUITE_SEPARATOR.length());
    }

    /** Package-visible for contract tests. */
    static String targetJson(String id, String display, String baseDir) {
        return targetJson(id, display, baseDir, false, null, 0);
    }

    static String targetJson(String id, String display, String baseDir, boolean canRun) {
        return targetJson(id, display, baseDir, canRun, null, 0);
    }

    /**
     * One build target. Its {@code languageIds} are the languages module {@code i} compiles; a
     * module that compiles Scala carries the {@code scala} data Metals imports it by, any other
     * the {@code jvm} data naming its JDK. A module whose manifest names an {@code [application]}
     * main is tagged {@code application}, every other {@code library}.
     */
    static String targetJson(
            String id, String display, String baseDir, boolean canRun, @Nullable IdeWireModel model, int i) {
        List<String> languages = model == null ? List.of() : model.languagesOf(i);
        JsonFields target = JsonFields.object()
                .token("id", BspServer.uriJson(id))
                .string("displayName", display)
                .string("baseDirectory", baseDir)
                .array("tags", List.of(canRun ? "application" : "library"))
                .array("languageIds", languages.isEmpty() ? LANGUAGE_IDS : languages)
                .token("dependencies", BspServer.arrayOf(dependencyIds(id, model, i)))
                .token(
                        "capabilities",
                        JsonFields.object()
                                .bool("canCompile", true)
                                .bool("canTest", true)
                                .bool("canRun", canRun)
                                .finish());
        if (model != null) {
            String scala = BspTargetOptions.scalaTargetData(model, i);
            String jvm = scala == null ? BspTargetOptions.jvmTargetData(model, i) : null;
            if (scala != null)
                target.string("dataKind", BspTargetOptions.SCALA_DATA_KIND).token("data", scala);
            else if (jvm != null) target.string("dataKind", "jvm").token("data", jvm);
        }
        return target.finish();
    }

    /**
     * The targets of the workspace modules module {@code i} depends on, main or test scope alike,
     * as identifiers beside {@code id}: BSP's {@code dependencies} are the direct upstream targets.
     */
    private static List<String> dependencyIds(String id, @Nullable IdeWireModel model, int i) {
        if (model == null) return List.of();
        String rootUri = id.substring(0, Math.max(0, id.indexOf('#')));
        List<String> out = new ArrayList<>();
        for (String name : BspTargetOptions.siblingNames(model, i)) {
            String dep = BspServer.uriJson(rootUri + "#" + name);
            if (!out.contains(dep)) out.add(dep);
        }
        return out;
    }

    /** The languages this server compiles when the workspace's own set is not known. */
    static final List<String> LANGUAGE_IDS = List.of("java", "kotlin", "groovy", "scala");
}
