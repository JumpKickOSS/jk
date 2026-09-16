// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.Set;

/**
 * Canonical step-name constants (the {@code {phase}-{step}} step names (their place in the run hierarchy is {@code plan/phase/step}) used by {@link
 * Task#builder} producers and their {@code .requires(...)} consumers). Naming a step in one
 * place kills the stringly-typed producer/consumer duplication where a typo would otherwise be a
 * silent missing-dependency edge rather than a compile error.
 */
public final class TaskNames {

    private TaskNames() {}

    public static final String ASSEMBLE_CLASSES = "assemble-classes";
    public static final String CACHE_INSTALL = "cache-install";
    public static final String COLLECT_SOURCES = "collect-sources";
    public static final String COMPARE_HASHES = "compare-hashes";
    public static final String COMPILE_GROOVY = "compile-groovy";
    public static final String COMPILE_JAVA = "compile-java";

    /**
     * The Mill-friendly alias of {@link #COMPILE_JAVA} that {@code jk explain}, the action cache
     * and the freshness stamps use. The live plan and the metrics store use {@code compile-java};
     * {@code EffortWeights.metricsStepName} is the single translation between the two.
     */
    public static final String COMPILE_MAIN = "compile-main";

    public static final String COMPILE_KOTLIN = "compile-kotlin";
    public static final String COMPILE_TEST = "compile-test";
    /** The Kotlin and Groovy halves of compile-test: action-cached under ids of their own. */
    public static final String COMPILE_TEST_KOTLIN = "compile-test-kotlin";

    public static final String COMPILE_TEST_GROOVY = "compile-test-groovy";

    /** Shared test helpers compiled to a directory that is never an artifact. */
    public static final String COMPILE_TEST_FIXTURES = "compile-test-fixtures";

    /** The guard suite ({@code src/guard/java}) compiled against main, the test classpath and jk-guards-junit. */
    public static final String COMPILE_GUARD = "compile-guard";

    public static final String COPY_RESOURCES = "copy-resources";

    /** Forecast-only: test-resource drift. Never discounts {@link #RUN_TESTS}. */
    public static final String COPY_TEST_RESOURCES = "copy-test-resources";

    /** Forecast-only: a sibling ordered-after this module is rebuilding. */
    public static final String ORDER_CHECK = "order-check";

    /** Forecast-only: outputs restored from the action cache instead of rebuilt. */
    public static final String RESTORE_OUTPUTS = "restore-outputs";
    /** Project build-logic, as the action-cache input label for one logic task. */
    public static final String BUILD_LOGIC = "build-logic";
    /** BEFORE_COMPILE / GENERATE anchor (codegen before main compile). */
    public static final String BUILD_LOGIC_BEFORE_COMPILE = "build-logic-before-compile";
    /** AFTER_COMPILE anchor. */
    public static final String BUILD_LOGIC_AFTER_COMPILE = "build-logic-after-compile";
    /** BEFORE_PACKAGE anchor. */
    public static final String BUILD_LOGIC_BEFORE_PACKAGE = "build-logic-before-package";
    /** AFTER_BUILD anchor — the workspace root's, after every member module has built. */
    public static final String BUILD_LOGIC_AFTER_BUILD = "build-logic-after-build";
    /** GUARD anchor — invocation-root scripts bound to {@code --guard} / {@code --scripts-only}. */
    public static final String BUILD_LOGIC_GUARD = "build-logic-guard";

    /** Guard lane: one module's bytecode rules, after its compile. */
    public static final String GUARD = "guard";
    /** Guard lane: model rules at the invocation root, before any compile. */
    public static final String GUARD_MODEL = "guard-model";
    /** Guard lane: cross-module bytecode rules at the root, after every compile. */
    public static final String GUARD_WORKSPACE = "guard-workspace";
    /** Guard lane: the tree scan at the root, on {@code --guard} and {@code jk guard}. */
    public static final String GUARD_TREE = "guard-tree";
    /** Guard lane: build-output rules at the root, after package / native / coverage. */
    public static final String GUARD_OUTPUT = "guard-output";

    /** The fixture proof at the root: every fixture-bearing rule bites; {@code --guard} and {@code jk guard} only. */
    public static final String GUARD_FIXTURES = "guard-fixtures";

    public static final String ENSURE_JDK = "ensure-jdk";
    public static final String FETCH_CATALOG = "fetch-catalog";
    public static final String FETCH_GIT = "fetch-git";
    public static final String IMAGE_PLAN = "image-plan";
    public static final String INSPECT_JAR = "inspect-jar";
    public static final String INSTALL_JDK = "install-jdk";
    public static final String LOCK_PLUGINS = "lock-plugins";
    public static final String LOCK_SDK = "lock-sdk";
    public static final String NATIVE_IMAGE = "native-image";
    /**
     * Opt-in reachability / AOT observation ({@code jk train}). Not on the default build terminal.
     */
    public static final String TRAIN = "train";

    public static final String PACKAGE_JAR = "package-jar";
    public static final String PACKAGE_ASSEMBLY = "package-assembly";

    /** R8-minified jar ({@code -min.jar}), built from the same inputs as the fat jar. */
    public static final String PACKAGE_MINIFIED = "package-minified";

    public static final String PACKAGE_SOURCES = "package-sources";

    /** {@code -javadoc.jar} for a library, built from its Java sources beside the module jar. */
    public static final String PACKAGE_JAVADOC = "package-javadoc";

    public static final String PARSE_BUILD = "parse-build";
    public static final String PARSE_LOCK = "parse-lock";
    public static final String PARSE_SCRIPT = "parse-script";
    public static final String QUERY_OSV = "query-osv";
    public static final String READ_LOCK = "read-lock";
    public static final String REBUILD_SCRATCH = "rebuild-scratch";
    public static final String RECONCILE_DEFAULT = "reconcile-default";
    public static final String RESOLVE_COORD = "resolve-coord";
    public static final String RESOLVE_DEPS = "resolve-deps";
    public static final String RESOLVE_FORMATTERS = "resolve-formatters";
    public static final String RESOLVE_JAR_DEPS = "resolve-jar-deps";
    public static final String RESOLVE_KOTLINC = "resolve-kotlinc";
    public static final String RUN_TESTS = "run-tests";

    /**
     * Packaging steps that require only {@link #PACKAGE_JAR} and therefore run <em>concurrently</em>
     * with {@link #RUN_TESTS} rather than after it.
     *
     * <p>Produced by {@code PlannerTails}, which adds exactly these as plan leaves beside the test
     * leaf. Listed here so cost/ETA models can price the overlap without restating the planner's
     * branch conditions: a module's wall is its compile prefix plus the longer of its test branch
     * and this tail, not the sum of its steps.
     */
    public static final Set<String> PACKAGING_TAILS =
            Set.of(PACKAGE_ASSEMBLY, PACKAGE_MINIFIED, NATIVE_IMAGE, PACKAGE_SOURCES, PACKAGE_JAVADOC);

    public static final String SET_DEFAULT = "set-default";
    public static final String SYNC_CAS = "sync-cas";
    public static final String SYNC_MODULES = "sync-modules";
    public static final String SYNC_PLUGINS = "sync-plugins";
    public static final String SYNC_SOURCES = "sync-sources";
    public static final String SYNC_WORKERS = "sync-workers";
    public static final String WRITE_IMAGE = "write-image";
    public static final String WRITE_LOCKFILE = "write-lockfile";
    public static final String WRITE_STAMP = "write-stamp";
    public static final String WRITE_STAMP_GROOVY = "write-stamp-groovy";
    public static final String WRITE_STAMP_KOTLIN = "write-stamp-kotlin";
    public static final String WRITE_SYNC_MANIFEST = "write-sync-manifest";
    public static final String DELETE = "delete";
    public static final String INSTALL = "install";

    /**
     * KSP codegen — the producer ({@code PlannerKsp}) / consumer ({@code compile-*.requires})
     * dependency edge. Single word, so {@code checkNoBareTaskName}'s hyphen filter cannot enforce
     * it; the constant is the contract, not the guard.
     */
    public static final String KSP = "ksp";

    public static final String PREWARM = "prewarm";
    public static final String SELECT = "select";
    public static final String WIZARD = "wizard";
    public static final String SCAFFOLD = "scaffold";
}
