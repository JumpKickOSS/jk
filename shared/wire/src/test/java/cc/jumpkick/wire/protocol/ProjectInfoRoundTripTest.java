// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Writer/reader symmetry for ProjectInfo's-era fields: decode a line
 * with every recently-added field populated, re-encode, and require a fixed point.
 */
class ProjectInfoRoundTripTest {

    @Test
    void encode_decode_is_a_fixed_point_for_the_new_fields() {
        String line = "{\"type\":\"" + EngineProtocol.PROJECT_INFO_ACK + "\""
                + ",\"group\":\"com.example\",\"name\":\"app\",\"version\":\"1.2.3\""
                + ",\"jdk\":\"temurin-25\",\"javaRelease\":25"
                + ",\"workspaceRoot\":true,\"workspaceRootDir\":\"/ws\""
                + ",\"modules\":{\"/ws/libs/a\":\"lib-a\",\"/ws/app\":\"app\"}"
                + ",\"mainClass\":\"com.example.Main\",\"application\":true"
                + ",\"assembly\":true,\"applicationConfig\":\"config/install.toml\""
                + ",\"nativeMode\":\"ALWAYS\",\"graal\":\"graalvm-25\""
                + ",\"hasLock\":true,\"lockJdk\":\"temurin-25.0.4\""
                + ",\"mainJarPath\":\"/ws/target/app-1.2.3.jar\""
                + ",\"assemblyJarPath\":\"/ws/target/app-1.2.3-all.jar\""
                + ",\"nativeBinPath\":\"/ws/target/app\""
                + ",\"nativeLibPath\":\"/ws/target/libapp.so\""
                + ",\"pathDeps\":[\"/ws/libs/a\"]"
                + ",\"sourcesJarPath\":\"/ws/target/app-sources.jar\""
                + ",\"javadocJarPath\":\"/ws/target/app-javadoc.jar\""
                + ",\"envRefs\":[\"DB_URL\",\"API \\\"KEY\\\"\"]"
                + ",\"sourceCount\":42,\"testCount\":7"
                + ",\"nativeExplicitlyDisabled\":true"
                + ",\"classesDir\":\"/ws/target/classes/main\""
                + ",\"testClassesDir\":\"/ws/target/classes/test\""
                + ",\"kotlinClassesDir\":\"/ws/target/classes/kotlin\""
                + ",\"groovyClassesDir\":\"/ws/target/classes/groovy\""
                + ",\"scala\":true,\"scalaVersion\":\"3.8.4\""
                + "}";
        ProjectInfo first = ProjectInfo.decode(line);
        String reEncoded = first.encode();
        // The fixture is a real wire line: the discriminator it carries is the one the engine
        // writes, so the re-encoded form is byte-comparable on that key rather than merely
        // "decodes to the same record".
        assertThat(EngineProtocol.typeOf(reEncoded)).isEqualTo(EngineProtocol.PROJECT_INFO_ACK);
        ProjectInfo second = ProjectInfo.decode(reEncoded);
        assertThat(second).isEqualTo(first);
        // Spot-check the values actually landed (a decoder that defaults everything would
        // pass the fixed-point test trivially).
        assertThat(first.moduleNames()).containsExactly("lib-a", "app");
        assertThat(first.envRefs()).containsExactly("DB_URL", "API \"KEY\"");
        assertThat(first.sourceCount()).isEqualTo(42);
        assertThat(first.lockJdk()).isEqualTo("temurin-25.0.4");
        assertThat(first.nativeExplicitlyDisabled()).isTrue();
        assertThat(first.kotlinClassesDir()).isEqualTo("/ws/target/classes/kotlin");
        assertThat(first.applicationConfig()).isEqualTo("config/install.toml");
        assertThat(first.assembly()).isTrue();
        assertThat(first.scala()).isTrue();
        assertThat(first.scalaVersion()).isEqualTo("3.8.4");
    }
}
