// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpdxLicensesTest {

    @Test
    void common_names_and_urls_map_to_spdx_ids() {
        assertThat(SpdxLicenses.id("The Apache Software License, Version 2.0", null))
                .isEqualTo("Apache-2.0");
        assertThat(SpdxLicenses.id(null, "https://www.apache.org/licenses/LICENSE-2.0.txt"))
                .isEqualTo("Apache-2.0");
        assertThat(SpdxLicenses.id("EPL 2.0", null)).isEqualTo("EPL-2.0");
        assertThat(SpdxLicenses.id("Eclipse Distribution License - v 1.0", null))
                .isEqualTo("BSD-3-Clause");
        assertThat(SpdxLicenses.id("GNU General Public License, version 2, with the Classpath Exception", null))
                .isEqualTo("GPL-2.0-with-classpath-exception");
        assertThat(SpdxLicenses.id("GNU General Public License, version 2 (GPL2), with the classpath exception", null))
                .isEqualTo("GPL-2.0-with-classpath-exception");
    }

    @Test
    void an_unknown_licence_keeps_its_name_and_duplicates_collapse() {
        assertThat(SpdxLicenses.of(List.of(
                        new Pom.License("Apache 2.0", null),
                        new Pom.License(null, "http://www.apache.org/licenses/LICENSE-2.0"),
                        new Pom.License("Acme Proprietary", "https://acme.example/license"))))
                .containsExactly("Apache-2.0", "Acme Proprietary");
    }
}
