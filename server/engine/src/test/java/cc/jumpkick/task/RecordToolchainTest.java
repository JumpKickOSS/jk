// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.ActionRecordText;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a record says of the toolchain and platform beside its inputs: the JDK build every key
 * names goes to {@code TOOLCHAIN}, the platform a platform-bound step keys goes to {@code TARGET},
 * and a record whose outputs run anywhere names no target. {@code jk explain} names a JDK that moved
 * by its build.
 */
class RecordToolchainTest {

    private static final String TEMURIN = "toolchain:jdk:eclipse-adoptium/25.0.4.1+1-LTS/0123456789abcdef";
    private static final String NEWER = "toolchain:jdk:eclipse-adoptium/25.0.5+11-LTS/fedcba9876543210";
    private static final ActionRecordText.Meta META =
            ActionRecordText.Meta.now(null, null, "jk/0.1.0", "linux-x86_64", 10, Map.of());

    @Test
    void a_compile_record_names_its_toolchain_and_no_target() {
        ActionRecordText.Meta meta =
                RecordToolchain.withPlatform(META, "k", Map.of("src/A.java", "abc", "jdk", TEMURIN));
        assertThat(meta.toolchain()).isEqualTo(TEMURIN);
        assertThat(meta.target()).isNull();
        assertThat(meta.host()).isEqualTo("linux-x86_64");
    }

    @Test
    void a_native_image_record_names_its_target() {
        String tokens = String.join(
                ";", "cp:abc", "graal:" + TEMURIN, RecordToolchain.TARGET_TOKEN + "macos-aarch64", "main:app.Main");
        ActionRecordText.Meta meta = RecordToolchain.withPlatform(META, "k", Map.of("inputs", tokens));
        assertThat(meta.toolchain()).isEqualTo(TEMURIN);
        assertThat(meta.target()).isEqualTo("macos-aarch64");
    }

    @Test
    void a_record_with_no_toolchain_is_left_as_it_was() {
        assertThat(RecordToolchain.withPlatform(META, "k", Map.of("inputs", "classes:dir:abc;main:App")))
                .isSameAs(META);
    }

    @Test
    void a_jdk_that_moved_is_named_by_both_builds() {
        assertThat(ActionKey.missReason(Map.of("jdk", TEMURIN), Map.of("jdk", NEWER)))
                .isEqualTo("JDK changed (eclipse-adoptium 25.0.4.1+1-LTS → eclipse-adoptium 25.0.5+11-LTS)");
    }
}
