// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.engine.protocol.ProjectInfo;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * First-party build plan tasks for {@code jk tasks} / {@code jk show} / {@code jk inspect}.
 * Names align with {@link TaskNames} where possible; a few Mill-friendly aliases
 * ({@code compile-main}, {@code package}) resolve to the same entry.
 */
final class TaskCatalog {

    record TaskDef(
            String name,
            String stage,
            String description,
            Function<BuildLayout, Path> primaryOutput,
            List<String> aliases) {

        Optional<Path> output(BuildLayout layout) {
            if (primaryOutput == null || layout == null) return Optional.empty();
            return Optional.ofNullable(primaryOutput.apply(layout));
        }
    }

    static Optional<Path> output(TaskDef task, ProjectInfo info) {
        if (task.primaryOutput == null || info == null) return Optional.empty();
        String path =
                switch (task.name()) {
                    case TaskNames.COMPILE_JAVA,
                            TaskNames.ASSEMBLE_CLASSES,
                            TaskNames.COPY_RESOURCES,
                            TaskNames.BUILD_LOGIC_AFTER_COMPILE,
                            TaskNames.BUILD_LOGIC_BEFORE_PACKAGE -> info.classesDir();
                    case TaskNames.COMPILE_KOTLIN -> info.kotlinClassesDir();
                    case TaskNames.COMPILE_GROOVY -> info.groovyClassesDir();
                    case TaskNames.COMPILE_TEST -> info.testClassesDir();
                    case TaskNames.RUN_TESTS -> info.testResultsDir();
                    case TaskNames.PACKAGE_JAR -> info.mainJarPath();
                    case TaskNames.PACKAGE_ASSEMBLY -> info.assemblyJarPath();
                    default -> "";
                };
        if (path == null || path.isBlank()) return Optional.empty();
        return Optional.of(Path.of(path));
    }

    private static final List<TaskDef> BUILD_TASKS = List.of(
            def(TaskNames.PARSE_BUILD, "Parse jk.toml / workspace modules", null),
            def(TaskNames.RESOLVE_DEPS, "Resolve dependencies / lock materialize", null),
            def(TaskNames.ENSURE_JDK, "Ensure configured JDK is available", null),
            def(TaskNames.BUILD_LOGIC_BEFORE_COMPILE, "Project build-logic (BEFORE_COMPILE)", null),
            def(
                    TaskNames.COMPILE_JAVA,
                    "Compile main Java sources",
                    BuildLayout::classesDir,
                    TaskNames.COMPILE_MAIN,
                    "compile"),
            def(TaskNames.COMPILE_KOTLIN, "Compile main Kotlin sources", BuildLayout::kotlinClassesDir),
            def(TaskNames.COMPILE_GROOVY, "Compile main Groovy sources", BuildLayout::groovyClassesDir),
            def(TaskNames.ASSEMBLE_CLASSES, "Merge language outputs into classes/main", BuildLayout::classesDir),
            def(TaskNames.BUILD_LOGIC_AFTER_COMPILE, "Project build-logic (AFTER_COMPILE)", BuildLayout::classesDir),
            def(
                    TaskNames.COPY_RESOURCES,
                    "Copy main resources + AFTER_RESOURCES build-logic",
                    BuildLayout::classesDir,
                    "resources"),
            def(TaskNames.COMPILE_TEST, "Compile test sources", BuildLayout::testClassesDir),
            def(TaskNames.RUN_TESTS, "Run tests", BuildLayout::testResultsDir, "test"),
            def(TaskNames.BUILD_LOGIC_BEFORE_PACKAGE, "Project build-logic (BEFORE_PACKAGE)", BuildLayout::classesDir),
            def(TaskNames.PACKAGE_JAR, "Package main jar", BuildLayout::mainJar, "package", "jar"),
            def(TaskNames.PACKAGE_ASSEMBLY, "Package assembly (fat) jar", BuildLayout::assemblyJar, "assembly"),
            def(TaskNames.WRITE_STAMP, "Write Java compile freshness stamp", null),
            def(TaskNames.WRITE_STAMP_KOTLIN, "Write Kotlin compile freshness stamp", null),
            def(TaskNames.WRITE_STAMP_GROOVY, "Write Groovy compile freshness stamp", null));

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

    private static TaskDef def(String name, String description, Function<BuildLayout, Path> out, String... aliases) {
        // Stage comes from the one taxonomy the whole system speaks: the catalog once
        // said `setup` where BuildStage says `resolve`, and hand-assigned stages drifted from
        // the inference (`write-stamp` is COMPILE, not package).
        return new TaskDef(name, BuildStage.ofTaskName(name).wireName(), description, out, List.of(aliases));
    }
}
