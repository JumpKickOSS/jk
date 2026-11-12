// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.layout.Languages;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.task.KeyOwner;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Which compilers a module's plan runs and how their outputs meet, from its effective manifest,
 * its source tree and its plugins' declarations. The plan is composed from it and the forecast
 * prices each compile against it, so a compile request built on one side is the request the other
 * keys.
 *
 * @param java javac runs: Java or Scala sources, or a Kotlin or Groovy module whose processors or
 *     plugin steps can generate Java
 * @param ksp a Kotlin module that declares {@code [processor-dependencies]} runs a KSP round
 * @param coordinatorOnly a workspace root with no source tree: nothing compiles or packages
 */
record ModuleShape(
        boolean java,
        boolean kotlin,
        boolean groovy,
        boolean scala,
        boolean compact,
        boolean ksp,
        boolean coordinatorOnly) {

    /** The shape a plan assumes when the manifest cannot be read: parse-build reports why. */
    static final ModuleShape UNREAD = new ModuleShape(true, false, false, false, false, false, false);

    /**
     * {@code project}'s shape at {@code dir}. Explicit {@code jk.toml} language opt-ins win;
     * otherwise the languages are inferred from the source tree ({@link
     * CompileSupport#resolveLanguages}). {@code decls} are the module's plugin declarations, null
     * when it has none.
     */
    @KeyOwner(step = {TaskNames.COMPILE_JAVA, TaskNames.COMPILE_KOTLIN, TaskNames.COMPILE_GROOVY, TaskNames.KSP})
    static ModuleShape of(JkBuild project, Path dir, @Nullable PluginDeclarations decls) {
        boolean compact = CompileSupport.isSimpleLayout(project.project(), dir);
        if (CompileSupport.coordinatorOnly(project, dir)) {
            return new ModuleShape(false, false, false, false, compact, false, true);
        }
        Languages langs = CompileSupport.resolveLanguages(project.project(), dir);
        boolean java = langs.java() || langs.scala();
        boolean processors = hasProcessorDeps(project);
        // [processor-dependencies] on a Kotlin module can generate Java sources (Hilt's components
        // are Java), and a plugin's generated sources can be Java in a Kotlin- or Groovy-only module
        // (protoc's --kotlin_out DSL wraps its own --java_out classes): javac compiles them.
        if (!java && langs.kotlin() && processors) java = true;
        if (!java && (langs.kotlin() || langs.groovy()) && contributesSources(decls)) java = true;
        return new ModuleShape(
                java, langs.kotlin(), langs.groovy(), langs.scala(), compact, langs.kotlin() && processors, false);
    }

    /** The module declares {@code [processor-dependencies]}. */
    static boolean hasProcessorDeps(JkBuild build) {
        List<Dependency> procs = build.dependencies().byScope().get(Scope.PROCESSOR);
        return procs != null && !procs.isEmpty();
    }

    /**
     * The module declares a processor compile-test reads: either table's, or the test table's alone
     * under its {@code inherit = false}.
     */
    static boolean hasTestProcessorDeps(JkBuild build) {
        for (Scope scope : ProcessorPaths.testScopes(build)) {
            List<Dependency> declared = build.dependencies().byScope().get(scope);
            if (declared != null && !declared.isEmpty()) return true;
        }
        return false;
    }

    private static boolean contributesSources(@Nullable PluginDeclarations decls) {
        if (decls == null) return false;
        for (TaskDecl step : decls.steps()) {
            if (!step.contributesSources().isEmpty()) return true;
        }
        return false;
    }

    /**
     * Kotlin and Java in one module: Kotlin compiles first (it reads Java declarations from source),
     * then javac against its output, then {@code assemble-classes} merges both trees.
     */
    boolean mixed() {
        return java && kotlin;
    }

    /** Groovy and Java in one module: joint groovyc first, emitting stubs javac reads, then javac. */
    boolean mixedGroovy() {
        return java && groovy;
    }

    /** The step the module's main compile ends with: what the steps after it wait on. */
    String mainCompile() {
        if (mixed() || mixedGroovy()) return TaskNames.ASSEMBLE_CLASSES;
        if (groovy) return TaskNames.COMPILE_GROOVY;
        return kotlin ? TaskNames.COMPILE_KOTLIN : TaskNames.COMPILE_JAVA;
    }
}
