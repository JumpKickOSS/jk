// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GroupInitialsTest {

    @Test
    void each_dot_segment_shrinks_to_its_initial() {
        assertThat(GroupInitials.module("org.apache.maven:maven-core")).isEqualTo("o.a.m:maven-core");
        assertThat(GroupInitials.module("com.uber.nullaway:nullaway")).isEqualTo("c.u.n:nullaway");
        assertThat(GroupInitials.module("jakarta.ws.rs:jakarta.ws.rs-api")).isEqualTo("j.w.r:jakarta.ws.rs-api");
    }

    @Test
    void two_segment_and_digit_groups() {
        assertThat(GroupInitials.module("io.netty:netty-all")).isEqualTo("i.n:netty-all");
        assertThat(GroupInitials.group("org.eclipse.jgit")).isEqualTo("o.e.j");
        assertThat(GroupInitials.group("com.h2database")).isEqualTo("c.h");
        assertThat(GroupInitials.group("io.7mind.izumi")).isEqualTo("i.7.i");
    }

    @Test
    void single_segment_group_is_unchanged() {
        assertThat(GroupInitials.group("junit")).isEqualTo("junit");
        assertThat(GroupInitials.module("junit:junit")).isEqualTo("junit:junit");
    }

    @Test
    void a_bare_artifact_has_no_group_to_shorten() {
        assertThat(GroupInitials.module("maven-core")).isEqualTo("maven-core");
        assertThat(GroupInitials.module("")).isEmpty();
        assertThat(GroupInitials.group("")).isEmpty();
    }

    @Test
    void only_the_group_changes() {
        assertThat(GroupInitials.module("org.apache.maven:maven-core:jar:tests"))
                .isEqualTo("o.a.m:maven-core:jar:tests");
    }
}
