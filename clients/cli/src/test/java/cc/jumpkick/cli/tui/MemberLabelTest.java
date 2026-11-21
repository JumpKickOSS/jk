// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MemberLabelTest {

    @Test
    void shorten_strips_package_fqcns() {
        assertThat(MemberLabel.shorten("cc.jumpkick.runtime.FooTest.freshen(java.nio.file.Path)"))
                .isEqualTo("FooTest.freshen(Path)");
        assertThat(MemberLabel.shorten("FooTest.bar(java.lang.String, java.util.List)"))
                .isEqualTo("FooTest.bar(String, List)");
        assertThat(MemberLabel.shorten("org.opentest4j.AssertionFailedError")).isEqualTo("AssertionFailedError");
        assertThat(MemberLabel.shorten("FooTest.bar(Path)  [w2]")).isEqualTo("FooTest.bar(Path)  [w2]");
        assertThat(MemberLabel.shorten("bar(java.nio.file.Path)")).isEqualTo("bar(Path)");
        assertThat(MemberLabel.shorten("foo(java.lang.String)[#2]")).isEqualTo("foo(String)[#2]");
        // Prose / versions / jars must not be mangled.
        assertThat(MemberLabel.shorten("package jk-engine-0.12.0.jar")).isEqualTo("package jk-engine-0.12.0.jar");
        assertThat(MemberLabel.shorten("compiling 12 sources")).isEqualTo("compiling 12 sources");
        assertThat(MemberLabel.shorten("1.2.3")).isEqualTo("1.2.3");
    }
}
