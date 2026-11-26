// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The host's release name uses the vocabulary the installers derive from {@code uname}. */
class ReleasePlatformTest {

    @Test
    void the_os_is_named_as_the_installers_name_it() {
        assertThat(ReleasePlatform.os("Linux")).isEqualTo("linux");
        assertThat(ReleasePlatform.os("Mac OS X")).isEqualTo("macos");
        assertThat(ReleasePlatform.os("Windows 11")).isEqualTo("windows");
        assertThat(ReleasePlatform.os("FreeBSD")).isEqualTo("freebsd");
        assertThat(ReleasePlatform.os("SunOS")).isEqualTo("sunos");
        assertThat(ReleasePlatform.os("")).isEqualTo("unknown");
    }

    @Test
    void the_arch_is_named_as_the_installers_name_it() {
        assertThat(ReleasePlatform.arch("amd64")).isEqualTo("x86_64");
        assertThat(ReleasePlatform.arch("arm64")).isEqualTo("aarch64");
        assertThat(ReleasePlatform.arch("aarch64")).isEqualTo("aarch64");
        assertThat(ReleasePlatform.arch("ppc64le")).isEqualTo("ppc64le");
    }

    @Test
    void the_host_is_os_dash_arch() {
        assertThat(ReleasePlatform.current()).matches("[a-z0-9_]+-[a-z0-9_]+");
    }

    @Test
    void the_known_platforms_are_spelled_as_the_host_names_itself() {
        assertThat(ReleasePlatform.KNOWN)
                .allMatch(p -> p.equals(ReleasePlatform.os(p.substring(0, p.indexOf('-'))) + "-"
                        + ReleasePlatform.arch(p.substring(p.indexOf('-') + 1))));
        assertThat(ReleasePlatform.windows("windows-x86_64")).isTrue();
        assertThat(ReleasePlatform.windows("linux-x86_64")).isFalse();
    }
}
