// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.task.ToolchainPolicy.Expectation;
import cc.jumpkick.task.ToolchainPolicy.Match;
import cc.jumpkick.task.ToolchainPolicy.Requirement;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The reader's policy over a record's TOOLCHAIN (JK-74S9K): {@code major} by default takes any
 * vendor and patch of this build's major, {@code level} any JDK at or above the module's level,
 * {@code build} only the same build; a JDK below the level never; a binding requirement only what it
 * admits; and among acceptable records the closest first.
 */
class ToolchainPolicyTest {

    private static final String TEMURIN_25_0_5 = "toolchain:jdk:eclipse-adoptium/25.0.5+11-LTS/aaaaaaaaaaaaaaaa";
    private static final String TEMURIN_25_0_1 = "toolchain:jdk:eclipse-adoptium/25.0.1+8-LTS/bbbbbbbbbbbbbbbb";
    private static final String CORRETTO_25 = "toolchain:jdk:amazon.com-inc./25.0.5+11-LTS/cccccccccccccccc";
    private static final String OPENJDK_27 = "toolchain:jdk:oracle-corporation/27.0.1+9/dddddddddddddddd";
    private static final String CORRETTO_21 = "toolchain:jdk:amazon.com-inc./21.0.8+9-LTS/eeeeeeeeeeeeeeee";
    private static final String TEMURIN_17 = "toolchain:jdk:eclipse-adoptium/17.0.20+8/ffffffffffffffff";

    @Test
    void major_takes_any_vendor_and_patch_of_the_same_major_only() {
        Expectation onTemurin25 = new Expectation(TEMURIN_25_0_5, 21, Match.MAJOR, null);
        assertThat(onTemurin25.accepts(TEMURIN_25_0_5)).isTrue();
        assertThat(onTemurin25.accepts(TEMURIN_25_0_1)).as("another patch").isTrue();
        assertThat(onTemurin25.accepts(CORRETTO_25)).as("another vendor").isTrue();
        assertThat(onTemurin25.accepts(OPENJDK_27)).as("another major").isFalse();
        assertThat(onTemurin25.accepts(CORRETTO_21)).as("another major").isFalse();
    }

    @Test
    void level_takes_any_jdk_at_or_above_the_module_level_and_never_below() {
        Expectation onJdk27 = new Expectation(OPENJDK_27, 21, Match.LEVEL, null);
        assertThat(onJdk27.accepts(TEMURIN_25_0_5))
                .as("the owner's example: 25.0.5 serves a host on 27")
                .isTrue();
        assertThat(onJdk27.accepts(CORRETTO_21)).isTrue();
        assertThat(onJdk27.accepts(TEMURIN_17))
                .as("below the module's level, never")
                .isFalse();
    }

    @Test
    void build_takes_only_the_same_build() {
        Expectation exact = new Expectation(TEMURIN_25_0_5, 21, Match.BUILD, null);
        assertThat(exact.accepts(TEMURIN_25_0_5)).isTrue();
        assertThat(exact.accepts(TEMURIN_25_0_1)).isFalse();
        assertThat(exact.accepts(CORRETTO_25)).isFalse();
    }

    @Test
    void a_binding_requirement_rejects_what_it_does_not_admit_at_any_match() {
        Requirement corretto21 = new Requirement("corretto", "21");
        Expectation required = new Expectation(CORRETTO_21, 21, Match.LEVEL, corretto21);
        assertThat(required.accepts(CORRETTO_21)).isTrue();
        assertThat(required.accepts("toolchain:jdk:amazon.com-inc./21.0.3+9-LTS/1111111111111111"))
                .as("another patch of Corretto 21")
                .isTrue();
        assertThat(required.accepts(TEMURIN_25_0_5))
                .as("another vendor and major")
                .isFalse();
        assertThat(required.accepts(CORRETTO_25)).as("Corretto, another major").isFalse();

        assertThat(new Expectation(CORRETTO_21, 21, Match.LEVEL, null).accepts(TEMURIN_25_0_5))
                .as("a suggestion (required = false) chooses the JDK, not the records")
                .isTrue();
        Requirement floor = new Requirement("", ">=25.0.5");
        Expectation floored = new Expectation(TEMURIN_25_0_5, 25, Match.MAJOR, floor);
        assertThat(floored.accepts(TEMURIN_25_0_5)).isTrue();
        assertThat(floored.accepts("toolchain:jdk:eclipse-adoptium/25.0.4.1+1-LTS/2222222222222222"))
                .as("below the patch floor")
                .isFalse();
        assertThat(floored.accepts(CORRETTO_25)).as("any vendor at the floor").isTrue();
    }

    @Test
    void the_closest_record_is_preferred() {
        Expectation onTemurin25 = new Expectation(TEMURIN_25_0_5, 21, Match.LEVEL, null);
        List<String> byCloseness = List.of(OPENJDK_27, CORRETTO_25, TEMURIN_25_0_1, TEMURIN_25_0_5).stream()
                .sorted((a, b) -> Integer.compare(onTemurin25.closeness(a), onTemurin25.closeness(b)))
                .toList();
        assertThat(byCloseness).containsExactly(TEMURIN_25_0_5, TEMURIN_25_0_1, CORRETTO_25, OPENJDK_27);
    }

    @Test
    void preview_and_an_unparseable_jdk_hold_to_the_exact_or_the_major() {
        assertThat(ToolchainPolicy.tiedToMajor(List.of("--enable-preview"))).isTrue();
        assertThat(ToolchainPolicy.tiedToMajor(List.of("-parameters"))).isFalse();
        Expectation onContent = new Expectation("toolchain:jdk:modules:abc", 21, Match.LEVEL, null);
        assertThat(onContent.accepts("toolchain:jdk:modules:abc")).isTrue();
        assertThat(onContent.accepts(TEMURIN_25_0_5)).isFalse();
        assertThat(Match.parse("Major")).contains(Match.MAJOR);
        assertThat(Match.parse("nope")).isEmpty();
    }
}
