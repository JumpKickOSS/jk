// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** One registered task, as declared over the describe protocol. */
public record TaskDecl(
        String name,
        List<String> requires,
        List<String> inputs,
        List<String> outputs,
        List<String> contributesClasses,
        List<String> contributesResources,
        List<String> contributesSources,
        /** Output dirs compile-test folds into the test source set ({@code contributesTestSources}). */
        List<String> contributesTestSources,
        List<String> contributesTestClasspath,
        /** Output files whose lines ride every forked test JVM ({@code contributesTestJvmArgs}). */
        List<String> contributesTestJvmArgs,
        /** The classes-replacing output dir ({@code TaskSpec.transformsClasses}), or null. */
        @Nullable String transformsClasses,
        /**
         * Optional product stage wire ({@code generate}, {@code compile}, …). Empty/null → engine
         * infers from contributions / name.
         */
        @Nullable String stage,
        /** Whether the module's tests run in one JVM ({@code TaskSpec.oneTestJvm}). */
        boolean oneTestJvm) {

    /** True when this task replaces the module's classes dir downstream. */
    public boolean transforms() {
        return transformsClasses != null && !transformsClasses.isBlank();
    }

    /** True when this task feeds a compiler source set, main or test. */
    public boolean sourceGenerating() {
        return (contributesSources != null && !contributesSources.isEmpty()) || testSourceGenerating();
    }

    /** True when this task feeds the test compiler's source set. */
    public boolean testSourceGenerating() {
        return contributesTestSources != null && !contributesTestSources.isEmpty();
    }

    /** True when this task feeds the forked test JVM: its classpath, its arguments, or both. */
    public boolean feedsTests() {
        return (contributesTestClasspath != null && !contributesTestClasspath.isEmpty())
                || (contributesTestJvmArgs != null && !contributesTestJvmArgs.isEmpty());
    }

    /** True when this task only contributes to the forked test JVM. */
    public boolean testOnly() {
        return feedsTests()
                && !sourceGenerating()
                && (contributesClasses == null || contributesClasses.isEmpty())
                && (contributesResources == null || contributesResources.isEmpty())
                && !transforms();
    }

    /**
     * True when package/classes consumers must wait on this task (post-compile work, transforms,
     * class/resource contributions — not pure source generation).
     */
    public boolean packageTime() {
        if (sourceGenerating() && !transforms() && (contributesClasses == null || contributesClasses.isEmpty())) {
            return false;
        }
        if (testOnly()) return false;
        return transforms()
                || (contributesClasses != null && !contributesClasses.isEmpty())
                || (contributesResources != null && !contributesResources.isEmpty())
                || (inputs != null && inputs.contains("classes"));
    }
}
