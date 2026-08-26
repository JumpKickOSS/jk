// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Synthetic-fixture coverage for {@link EnvVarProbe}: the environment is a map, the tool homes are
 * {@code @TempDir} layouts, so every branch runs on every machine. Nothing here reads the host's
 * real {@code JAVA_HOME}.
 */
class EnvVarProbeTest {

    private static EnvVarProbe probe(Map<String, String> env) {
        return new EnvVarProbe(env::get);
    }

    @Test
    void java_home_matching_the_requested_version_is_a_hit(@TempDir Path tmp) throws Exception {
        Path home = ToolHealthTest.jdkLayout(tmp, "21.0.5", "Eclipse Adoptium");

        Optional<DiscoveredTool> hit =
                probe(Map.of("JAVA_HOME", home.toString())).find(ToolSpec.jdk("21.0.5", null));

        assertThat(hit).isPresent();
        assertThat(hit.get().home()).isEqualTo(home.toAbsolutePath().normalize());
        assertThat(hit.get().detectedVersion()).isEqualTo("21.0.5");
        assertThat(hit.get().source()).isEqualTo("java-home:JAVA_HOME");
    }

    @Test
    void java_home_at_a_different_version_is_a_miss(@TempDir Path tmp) throws Exception {
        Path home = ToolHealthTest.jdkLayout(tmp, "21.0.5", "Eclipse Adoptium");

        assertThat(probe(Map.of("JAVA_HOME", home.toString())).find(ToolSpec.jdk("25.0.4", null)))
                .isEmpty();
    }

    @Test
    void a_requested_distribution_that_does_not_match_the_release_file_is_a_miss(@TempDir Path tmp) throws Exception {
        Path home = ToolHealthTest.jdkLayout(tmp, "21.0.5", "Eclipse Adoptium");
        Map<String, String> env = Map.of("JAVA_HOME", home.toString());

        assertThat(probe(env).find(ToolSpec.jdk("21.0.5", "tem"))).isPresent();
        assertThat(probe(env).find(ToolSpec.jdk("21.0.5", "graalce"))).isEmpty();
    }

    @Test
    void an_unset_blank_or_nonexistent_home_is_a_miss(@TempDir Path tmp) throws Exception {
        ToolSpec spec = ToolSpec.jdk("21.0.5", null);

        assertThat(probe(Map.of()).find(spec)).isEmpty();
        assertThat(probe(Map.of("JAVA_HOME", "   ")).find(spec)).isEmpty();
        assertThat(probe(Map.of("JAVA_HOME", tmp.resolve("absent").toString())).find(spec))
                .isEmpty();
    }

    @Test
    void kotlin_home_serves_the_kotlin_kind(@TempDir Path tmp) throws Exception {
        // The compiler manifest says 2.3.21-release-298; every catalog calls that 2.3.21.
        Path home = ToolHealthTest.kotlinLayout(tmp, "2.3.21-release-298");

        Optional<DiscoveredTool> hit =
                probe(Map.of("KOTLIN_HOME", home.toString())).find(ToolSpec.kotlin("2.3.21"));

        assertThat(hit).isPresent();
        assertThat(hit.get().detectedVersion()).isEqualTo("2.3.21");
        assertThat(hit.get().source()).isEqualTo("java-home:KOTLIN_HOME");
        // JAVA_HOME must not be consulted for a kotlin spec.
        assertThat(probe(Map.of("JAVA_HOME", home.toString())).find(ToolSpec.kotlin("2.3.21")))
                .isEmpty();
    }

    @Test
    void maven_home_is_a_synonym_for_m2_home_and_m2_home_wins(@TempDir Path tmp) throws Exception {
        Path home = ToolHealthTest.mavenLayout(tmp, "3.9.9");
        ToolSpec spec = ToolSpec.maven("3.9.9");

        assertThat(probe(Map.of("MAVEN_HOME", home.toString())).find(spec)).isPresent();
        assertThat(probe(Map.of("M2_HOME", home.toString())).find(spec))
                .get()
                .extracting(DiscoveredTool::source)
                .isEqualTo("java-home:M2_HOME");
        // Both set and both usable: M2_HOME is tried first, so it is the reported source.
        assertThat(probe(Map.of("M2_HOME", home.toString(), "MAVEN_HOME", home.toString()))
                        .find(spec))
                .get()
                .extracting(DiscoveredTool::source)
                .isEqualTo("java-home:M2_HOME");
    }

    @Test
    void gradle_home_serves_the_gradle_kind(@TempDir Path tmp) throws Exception {
        Path home = ToolHealthTest.gradleLayout(tmp, "9.5.1");

        assertThat(probe(Map.of("GRADLE_HOME", home.toString())).find(ToolSpec.gradle("9.5.1")))
                .get()
                .extracting(DiscoveredTool::source)
                .isEqualTo("java-home:GRADLE_HOME");
    }

    @Test
    void an_unknown_kind_consults_no_variable(@TempDir Path tmp) throws Exception {
        Path home = ToolHealthTest.jdkLayout(tmp, "21.0.5", "Eclipse Adoptium");

        assertThat(probe(Map.of(
                                "JAVA_HOME", home.toString(),
                                "KOTLIN_HOME", home.toString(),
                                "M2_HOME", home.toString(),
                                "GRADLE_HOME", home.toString()))
                        .find(new ToolSpec("scala", "21.0.5", null)))
                .isEmpty();
    }

    @Test
    void discover_all_jdks_reports_java_home_once_and_nothing_when_unset(@TempDir Path tmp) throws Exception {
        Path home = ToolHealthTest.jdkLayout(tmp, "21.0.5", "Eclipse Adoptium");

        assertThat(probe(Map.of("JAVA_HOME", home.toString())).discoverAllJdks())
                .singleElement()
                .satisfies(jdk -> {
                    assertThat(jdk.home()).isEqualTo(home.toRealPath());
                    assertThat(jdk.version()).isEqualTo("21.0.5");
                    assertThat(jdk.source()).isEqualTo("java-home");
                });

        assertThat(probe(Map.of()).discoverAllJdks()).isEmpty();
    }
}
