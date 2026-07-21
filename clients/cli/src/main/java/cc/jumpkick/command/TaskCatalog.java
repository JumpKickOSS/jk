// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.run.StepNames;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * First-party build pipeline tasks for {@code jk tasks} / {@code jk show} / {@code jk inspect}
 * (JK-1047). Names align with {@link StepNames} where possible; a few Mill-friendly aliases
 * ({@code compile-main}, {@code package}) resolve to the same entry.
 */
final class TaskCatalog {

    record TaskDef(
            String name,
            String phase,
            String description,
            Function<BuildLayout, Path> primaryOutput,
            List<String> aliases) {

        Optional<Path> output(BuildLayout layout) {
            if (primaryOutput == null || layout == null) return Optional.empty();
            return Optional.ofNullable(primaryOutput.apply(layout));
        }
    }

    private static final List<TaskDef> BUILD_TASKS = List.of(
            def(StepNames.PARSE_BUILD, "setup", "Parse jk.toml / workspace modules", null),
            def(StepNames.RESOLVE_DEPS, "setup", "Resolve dependencies / lock materialize", null),
            def(StepNames.ENSURE_JDK, "setup", "Ensure configured JDK is available", null),
            def(
                    StepNames.COMPILE_JAVA,
                    "compile",
                    "Compile main Java sources",
                    BuildLayout::classesDir,
                    "compile-main",
                    "compile"),
            def(StepNames.COMPILE_KOTLIN, "compile", "Compile main Kotlin sources", BuildLayout::kotlinClassesDir),
            def(StepNames.ASSEMBLE_CLASSES, "compile", "Merge language outputs into classes/main", BuildLayout::classesDir),
            def(
                    "build-logic-after-compile",
                    "compile",
                    "Project build-logic SPI (AFTER_COMPILE)",
                    BuildLayout::classesDir),
            def(
                    StepNames.COPY_RESOURCES,
                    "compile",
                    "Copy main resources + AFTER_RESOURCES build-logic",
                    BuildLayout::classesDir,
                    "resources"),
            def(StepNames.COMPILE_TEST, "test", "Compile test sources", BuildLayout::testClassesDir),
            def(StepNames.RUN_TESTS, "test", "Run tests", BuildLayout::testResultsDir, "test"),
            def(
                    "build-logic-before-package",
                    "package",
                    "Project build-logic SPI (BEFORE_PACKAGE)",
                    BuildLayout::classesDir),
            def(
                    StepNames.PACKAGE_JAR,
                    "package",
                    "Package main jar",
                    BuildLayout::mainJar,
                    "package",
                    "jar"),
            def(
                    StepNames.PACKAGE_ASSEMBLY,
                    "package",
                    "Package assembly (fat) jar",
                    BuildLayout::assemblyJar,
                    "assembly"),
            def(StepNames.WRITE_STAMP, "package", "Write Java compile freshness stamp", null),
            def(StepNames.WRITE_STAMP_KOTLIN, "package", "Write Kotlin compile freshness stamp", null));

    private static final Map<String, TaskDef> BY_NAME = index();

    private TaskCatalog() {}

    static List<TaskDef> buildTasks() {
        return BUILD_TASKS;
    }

    static Optional<TaskDef> find(String nameOrAlias) {
        if (nameOrAlias == null || nameOrAlias.isBlank()) return Optional.empty();
        return Optional.ofNullable(BY_NAME.get(nameOrAlias.trim().toLowerCase(Locale.ROOT)));
    }

    /** Canonical names only (for listing). */
    static List<String> canonicalNames() {
        List<String> out = new ArrayList<>(BUILD_TASKS.size());
        for (TaskDef t : BUILD_TASKS) out.add(t.name());
        return out;
    }

    private static Map<String, TaskDef> index() {
        Map<String, TaskDef> m = new LinkedHashMap<>();
        for (TaskDef t : BUILD_TASKS) {
            m.put(t.name().toLowerCase(Locale.ROOT), t);
            for (String a : t.aliases()) {
                m.putIfAbsent(a.toLowerCase(Locale.ROOT), t);
            }
        }
        return m;
    }

    private static TaskDef def(
            String name, String phase, String description, Function<BuildLayout, Path> out, String... aliases) {
        return new TaskDef(name, phase, description, out, List.of(aliases));
    }
}
