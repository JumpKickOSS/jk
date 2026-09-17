// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import org.junit.jupiter.api.Test;

class GradleImporterProcessorScopeTest {

    @Test
    void the_test_processor_configurations_import_into_the_test_processor_table() {
        JkBuild build = GradleImporter.importFromString("""
                        dependencies {
                            annotationProcessor("org.projectlombok:lombok:1.18.42")
                            testAnnotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")
                            kaptTest("com.google.dagger:dagger-compiler:2.57")
                        }
                        """, "app").jkBuild();

        assertThat(build.dependencies().of(Scope.PROCESSOR))
                .extracting(Dependency::module)
                .containsExactly("org.projectlombok:lombok");
        assertThat(build.dependencies().of(Scope.TEST_PROCESSOR))
                .extracting(Dependency::module)
                .containsExactly("org.mapstruct:mapstruct-processor", "com.google.dagger:dagger-compiler");
    }
}
