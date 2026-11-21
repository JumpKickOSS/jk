// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.version;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VersionRangesTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[1.0,2.0)",
                "[1.0]",
                "(,2.0]",
                "[1.0,)",
                "(,)",
                "[1.0,2.0[",
                "]1.0,2.0]",
                "[1.0,2.0),[3.0,4.0]",
                ">=1.2,<2",
                ">1.0",
                "<=2",
                "=1.0",
                "[1.0,1.0]"
            })
    void a_range_the_resolver_reads_passes_the_check(String spec) {
        assertThatCode(() -> VersionRanges.check(spec)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"(1.0)", "[1.0)", "[]", "()", "[1.0,2.0", "[1.0,2.0),3.0]", ",", "[2.0,1.0]", "(1.0,1.0]"})
    void a_range_that_names_no_version_is_refused(String spec) {
        assertThatThrownBy(() -> VersionRanges.check(spec)).isInstanceOf(IllegalArgumentException.class);
    }
}
