// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.BuildIdentity;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class KeyContractTest {

    @Test
    void suite_code_is_the_test_package_and_the_classes_named_for_tests_suites_and_junit() {
        assertThat(KeyContract.suiteCode("cc/jumpkick/test/Discovery.class")).isTrue();
        assertThat(KeyContract.suiteCode("cc/jumpkick/test/PullWorkerPool$Lease.class"))
                .isTrue();
        assertThat(KeyContract.suiteCode("cc/jumpkick/runtime/SuiteEnvironment.class"))
                .isTrue();
        assertThat(KeyContract.suiteCode("cc/jumpkick/runtime/TagExcludedSuite.class"))
                .isTrue();
        assertThat(KeyContract.suiteCode("cc/jumpkick/runtime/base/SuiteSelection$1.class"))
                .isTrue();
        assertThat(KeyContract.suiteCode("cc/jumpkick/runtime/JUnitLineConflict.class"))
                .isTrue();
        assertThat(KeyContract.suiteCode("cc/jumpkick/config/TestSelection.class"))
                .isTrue();
    }

    @Test
    void the_rest_of_the_engine_is_not_suite_code() {
        assertThat(KeyContract.suiteCode("cc/jumpkick/runtime/PackagingKeys.class"))
                .isFalse();
        assertThat(KeyContract.suiteCode("cc/jumpkick/runtime/HardwareProbe$JunitPlatformTimes.class"))
                .as("a nested class is its top-level class's")
                .isFalse();
        assertThat(KeyContract.suiteCode("cc/jumpkick/test/sets/junit4.txt")).isFalse();
        assertThat(KeyContract.suiteCode("org/junit/platform/launcher/TestPlan.class"))
                .isFalse();
    }

    @Test
    void the_code_that_judges_a_suite_is_keyed_by_its_classes_not_by_the_contract() {
        assertThat(KeyContract.suiteCode("cc/jumpkick/orchestrator/ResultAggregator.class"))
                .isFalse();
        assertThat(KeyContract.suiteCode("cc/jumpkick/orchestrator/TestClassMatch.class"))
                .as("named for tests, but in the orchestrator's package")
                .isFalse();
        assertThat(KeyContract.SUITE.keeps(
                        "server/test-orchestrator/src/main/java/cc/jumpkick/orchestrator/TestClassMatch.java"))
                .isFalse();
    }

    /** The code that writes the stamp moves the key by what it writes, so the contract leaves it out. */
    @Test
    void the_code_that_writes_the_stamp_is_not_suite_code() {
        assertThat(KeyContract.suiteCode("cc/jumpkick/runtime/TestStampInputs.class"))
                .isFalse();
        assertThat(KeyContract.suiteCode("cc/jumpkick/runtime/TestStampInputs$Facts.class"))
                .as("a nested class is its top-level class's")
                .isFalse();
        assertThat(KeyContract.SUITE.keeps("server/engine/src/main/java/cc/jumpkick/runtime/TestStampInputs.java"))
                .isFalse();
        assertThat(KeyContract.suiteCode("cc/jumpkick/runtime/TestSteps.class"))
                .as("the step that launches the suite stays keyed")
                .isTrue();
    }

    /**
     * What a suite was found to read keys it as tokens, so the code that keeps and reads them back
     * moves the key by what it writes; the rules that decide which reads count are the orchestrator's.
     */
    @Test
    void the_code_that_keeps_a_suites_discovered_reads_is_not_suite_code() {
        assertThat(KeyContract.suiteCode("cc/jumpkick/test/SuiteReads.class")).isFalse();
        assertThat(KeyContract.suiteCode("cc/jumpkick/test/SuiteReads$Discovered.class"))
                .isFalse();
        assertThat(KeyContract.SUITE.keeps("server/engine/src/main/java/cc/jumpkick/test/SuiteReads.java"))
                .isFalse();
        assertThat(KeyContract.suiteCode("cc/jumpkick/orchestrator/DiscoveryRules.class"))
                .as("keyed by the orchestrator's classes")
                .isFalse();
        assertThat(KeyContract.suiteCode("cc/jumpkick/test/SuiteReadLists.class"))
                .as("the rest of the suite package stays keyed")
                .isTrue();
    }

    /** No key names the heap a suite JVM runs at, so the code that sizes it is not suite code either. */
    @Test
    void the_code_that_sizes_a_suites_heap_is_not_suite_code() {
        assertThat(KeyContract.suiteCode("cc/jumpkick/test/SuiteRetry.class")).isFalse();
        assertThat(KeyContract.SUITE.keeps("server/engine/src/main/java/cc/jumpkick/test/SuiteRetry.java"))
                .isFalse();
        assertThat(KeyContract.suiteCode("cc/jumpkick/orchestrator/RetryPolicy.class"))
                .as("which failures a retry answers is keyed by the orchestrator's classes")
                .isFalse();
        assertThat(KeyContract.suiteCode("cc/jumpkick/orchestrator/RetryLoop.class"))
                .as("when the retries stop is keyed by the orchestrator's classes")
                .isFalse();
    }

    /**
     * What a suite's JVMs start with is keyed by value, the fork spec in the stamp, so the code that
     * forks them is not suite code: an edit to it that leaves the spec as it was moves no key.
     */
    @Test
    void the_code_that_forks_a_suites_jvms_is_not_suite_code() {
        for (String source : List.of(
                "server/engine/src/main/java/cc/jumpkick/test/JUnitLauncher.java",
                "server/engine/src/main/java/cc/jumpkick/runtime/TestLaunch.java",
                "server/engine/src/main/java/cc/jumpkick/test/ForkSpec.java")) {
            assertThat(KeyContract.SUITE.keeps(source)).as(source).isFalse();
            assertThat(KeyContract.suiteCode(KeyContract.entryOf(source)))
                    .as(source)
                    .isFalse();
        }
        assertThat(KeyContract.suiteCode("cc/jumpkick/test/JUnitLauncher$Held.class"))
                .as("a nested class is its top-level class's")
                .isFalse();
        assertThat(KeyContract.suiteCode("cc/jumpkick/test/JUnitLauncher$1.class"))
                .isFalse();
        assertThat(KeyContract.suiteCode("cc/jumpkick/test/PullWorkerPool.class"))
                .as("the rest of the suite package stays keyed")
                .isTrue();
    }

    @Test
    void a_source_file_is_judged_by_the_class_it_compiles_to() {
        assertThat(KeyContract.entryOf("server/engine/src/main/java/cc/jumpkick/test/Discovery.java"))
                .isEqualTo("cc/jumpkick/test/Discovery.class");
        assertThat(KeyContract.SUITE.keeps("shared/core/src/main/java/cc/jumpkick/config/TestSelection.java"))
                .isTrue();
        assertThat(KeyContract.SUITE.keeps("server/engine/src/main/java/cc/jumpkick/runtime/PackagingKeys.java"))
                .isFalse();
    }

    /** Rewrapping or reindenting a file is not a change of its code; an edit to a statement is. */
    @Test
    void the_digest_reads_code_and_not_layout() {
        TreeMap<String, String> one = new TreeMap<>();
        one.put("a/A.java", "class A {\n    int x = 1;\n}\n");
        TreeMap<String, String> relaid = new TreeMap<>();
        relaid.put("a/A.java", "class A { int x = 1; }");
        TreeMap<String, String> edited = new TreeMap<>();
        edited.put("a/A.java", "class A { int x = 2; }");
        TreeMap<String, String> moved = new TreeMap<>();
        moved.put("b/A.java", "class A { int x = 1; }");

        assertThat(KeyContract.digest(relaid))
                .isEqualTo(KeyContract.digest(one))
                .hasSize(12);
        assertThat(KeyContract.digest(edited)).isNotEqualTo(KeyContract.digest(one));
        assertThat(KeyContract.digest(moved)).isNotEqualTo(KeyContract.digest(one));
    }

    /**
     * A contract's actions key by the version, the salt and the contract's digest, and nothing of
     * the running engine: another engine with the same code keys them alike.
     */
    @Test
    void a_contracts_key_version_moves_with_its_digest_and_not_with_the_engine() {
        try {
            BuildIdentity.overrideBuildIdForTests("engine000001");
            String one = KeyContract.ARTIFACT.keyVersion();
            assertThat(one).isEqualTo(BuildIdentity.cacheKeyVersion() + "+" + KeyContract.ARTIFACT.recorded());
            BuildIdentity.overrideBuildIdForTests("engine000002");
            assertThat(KeyContract.ARTIFACT.keyVersion()).isEqualTo(one);
            KeyContract.ARTIFACT.overrideDigestForTests("artifact0002");
            assertThat(KeyContract.ARTIFACT.keyVersion()).isNotEqualTo(one).endsWith("+artifact0002");
            assertThat(KeyContract.SUITE.keyVersion())
                    .as("each contract keys apart")
                    .endsWith("+" + KeyContract.SUITE.recorded());
        } finally {
            BuildIdentity.overrideBuildIdForTests(null);
            KeyContract.ARTIFACT.overrideDigestForTests(null);
        }
    }
}
