// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class JdkVendorTest {

    @Test
    void fromProperties_maps_graalvm_community_implementor_to_ce() {
        // SDKMAN / upstream GraalVM CE ships IMPLEMENTOR="GraalVM Community" (not Oracle).
        Properties props = new Properties();
        props.setProperty("IMPLEMENTOR", "\"GraalVM Community\"");
        props.setProperty("GRAALVM_VERSION", "\"25.2.4\"");

        assertThat(JdkVendor.fromProperties(props)).isEqualTo(JdkVendor.GRAALVM_CE);
        assertThat(JdkVendor.GRAALVM_CE.displayName()).isEqualTo("GraalVM Community");
    }

    @Test
    void fromProperties_refines_oracle_with_graalvm_version_to_oracle_graalvm() {
        Properties props = new Properties();
        props.setProperty("IMPLEMENTOR", "\"Oracle Corporation\"");
        props.setProperty("GRAALVM_VERSION", "\"25.0.0\"");

        assertThat(JdkVendor.fromProperties(props)).isEqualTo(JdkVendor.ORACLE_GRAALVM);
    }

    @Test
    void fromProperties_keeps_oracle_without_graal_markers_as_openjdk() {
        Properties props = new Properties();
        props.setProperty("IMPLEMENTOR", "\"Oracle Corporation\"");

        assertThat(JdkVendor.fromProperties(props)).isEqualTo(JdkVendor.ORACLE_OPENJDK);
    }
}
