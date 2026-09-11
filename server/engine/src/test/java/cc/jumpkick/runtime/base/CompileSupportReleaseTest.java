// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** An undeclared {@code java} level is the build JDK's, the way javac reads an absent --release. */
class CompileSupportReleaseTest {

    @Test
    void a_declared_level_stands_and_an_undeclared_one_is_the_build_jdk_s() {
        assertThat(CompileSupport.effectiveRelease(17, 25)).isEqualTo(17);
        assertThat(CompileSupport.effectiveRelease(0, 25)).isEqualTo(25);
    }

    @Test
    void the_kotlin_target_is_the_level_capped_at_what_kotlinc_accepts() {
        assertThat(CompileSupport.kotlinJvmTarget(17, 25)).isEqualTo(17);
        assertThat(CompileSupport.kotlinJvmTarget(25, 25)).isEqualTo(CompileSupport.KOTLIN_MAX_JVM_TARGET);
        assertThat(CompileSupport.kotlinJvmTarget(0, 25)).isEqualTo(CompileSupport.KOTLIN_MAX_JVM_TARGET);
        assertThat(CompileSupport.kotlinJvmTarget(0, 17)).isEqualTo(17);
    }
}
