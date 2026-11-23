// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.BuildIdentity;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class KeyContractTest {

    @Test
    void suite_code_is_the_test_package_and_the_classes_named_for_tests_suites_and_junit() {
        assertThat(KeyContract.suiteCode("cc/jumpkick/test/ResultAggregator.class"))
                .isTrue();
        assertThat(KeyContract.suiteCode("cc/jumpkick/test/PullWorkerPool$Lease.class"))
                .isTrue();
        assertThat(KeyContract.suiteCode("cc/jumpkick/runtime/TestLaunch.class"))
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
