// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.testing.FakeJdk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenToolchainsProbeTest {

    @Test
    void lists_every_jdk_home_and_expands_env_references(@TempDir Path tmp) throws IOException {
        Path plain = FakeJdk.create(tmp.resolve("jdks/temurin-21"), "21.0.5");
        Path viaEnv = FakeJdk.create(tmp.resolve("jdks/zulu-25"), "25.0.1");
        Path file = toolchains(
                tmp,
                toolchain("jdk", plain.toString()),
                toolchain("jdk", "${env.SOME_JDK}"),
                toolchain("jdk", "${env.UNSET_JDK}"),
                toolchain("netbeans", tmp.resolve("jdks/zulu-25").toString()));
        Map<String, String> env = Map.of("SOME_JDK", viaEnv.toString());

        List<JdkHit> hits = new MavenToolchainsProbe(file, env::get).discoverAllJdks();

        assertThat(hits).extracting(JdkHit::home).containsExactly(plain.toRealPath(), viaEnv.toRealPath());
        assertThat(hits).allSatisfy(h -> assertThat(h.source()).isEqualTo("maven-toolchains"));
    }

    @Test
    void an_unset_variable_leaves_that_home_out() {
        assertThat(MavenToolchains.expand("${env.NOPE}/jdk", name -> null)).isNull();
        assertThat(MavenToolchains.expand("/opt/${env.V}/jdk", Map.of("V", "21")::get))
                .isEqualTo("/opt/21/jdk");
    }

    @Test
    void a_missing_file_is_empty(@TempDir Path tmp) throws IOException {
        assertThat(new MavenToolchainsProbe(tmp.resolve("toolchains.xml"), name -> null).discoverAllJdks())
                .isEmpty();
    }

    @Test
    void a_broken_file_warns_and_is_empty(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("toolchains.xml"), "<toolchains><toolchain>");
        List<String> warnings = new ArrayList<>();

        assertThat(MavenToolchains.jdkHomes(file, name -> null, warnings::add)).isEmpty();
        assertThat(warnings).singleElement().asString().contains(file.toString());
        assertThat(new MavenToolchainsProbe(file, name -> null).discoverAllJdks())
                .isEmpty();
    }

    @Test
    void a_home_that_is_not_a_jdk_is_skipped(@TempDir Path tmp) throws IOException {
        Path file = toolchains(tmp, toolchain("jdk", tmp.resolve("gone").toString()));
        assertThat(new MavenToolchainsProbe(file, name -> null).discoverAllJdks())
                .isEmpty();
    }

    static Path toolchains(Path dir, String... toolchains) throws IOException {
        return Files.writeString(
                dir.resolve("toolchains.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<toolchains>\n" + String.join("\n", toolchains)
                        + "\n</toolchains>\n");
    }

    static String toolchain(String type, String jdkHome) {
        return "  <toolchain>\n    <type>" + type
                + "</type>\n    <provides><version>21</version></provides>\n"
                + "    <configuration><jdkHome>" + jdkHome + "</jdkHome></configuration>\n  </toolchain>";
    }
}
