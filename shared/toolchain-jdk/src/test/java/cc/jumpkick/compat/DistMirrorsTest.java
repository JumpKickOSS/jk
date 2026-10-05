// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.m2.MavenSettings;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Each tool distribution's origin: the environment over {@code [mirrors]} over a settings.xml mirror over the public base. */
class DistMirrorsTest {

    @TempDir
    Path dir;

    @Test
    void each_distribution_reads_its_own_env_mirror_key_settings_token_and_default() throws Exception {
        MavenSettings maven = settings("""
                <settings><mirrors>
                  <mirror><id>corp-kotlin</id><mirrorOf>kotlin</mirrorOf><url>https://nexus.corp/kotlin</url></mirror>
                  <mirror><id>corp-all</id><mirrorOf>*</mirrorOf><url>https://nexus.corp/maven/</url></mirror>
                </mirrors></settings>
                """);
        Map<String, String> mirrors = Map.of("gradle", "https://file.corp/gradle");
        Map<String, String> env = Map.of("JK_MAVEN_DIST_MIRROR", "https://env.corp/maven");

        assertThat(DistMirrors.origin(DistMirrors.Dist.MAVEN, env::get, mirrors, maven)
                        .url())
                .as("the environment first")
                .hasToString("https://env.corp/maven/");
        assertThat(DistMirrors.origin(DistMirrors.Dist.GRADLE, env::get, mirrors, maven)
                        .url())
                .as("then [mirrors]")
                .hasToString("https://file.corp/gradle/");
        assertThat(DistMirrors.origin(DistMirrors.Dist.KOTLIN, env::get, mirrors, maven))
                .as("then a settings.xml mirror naming the tool, with that mirror's credential id")
                .isEqualTo(new DownloadOrigin(URI.create("https://nexus.corp/kotlin/"), "corp-kotlin"));
        assertThat(DistMirrors.origin(DistMirrors.Dist.MAVEN, name -> null, Map.of(), maven)
                        .credentialId())
                .as("a wildcard mirror stands in for Central, so for the Maven distribution")
                .isEqualTo("corp-all");
        assertThat(DistMirrors.origin(DistMirrors.Dist.NODE, name -> null, Map.of(), maven)
                        .url())
                .as("a wildcard Maven mirror never stands in for nodejs.org")
                .hasToString(DistMirrors.Dist.NODE.publicBase());
    }

    @Test
    void the_resolvers_build_their_urls_under_the_configured_base() {
        String previous = System.getProperty("jk.env.JK_KOTLIN_DIST_MIRROR");
        System.setProperty("jk.env.JK_KOTLIN_DIST_MIRROR", "https://nexus.corp/kotlin");
        try {
            assertThat(KotlinResolver.distributionFor("2.4.10").downloadUri())
                    .hasToString("https://nexus.corp/kotlin/v2.4.10/kotlin-compiler-2.4.10.zip");
        } finally {
            if (previous == null) System.clearProperty("jk.env.JK_KOTLIN_DIST_MIRROR");
            else System.setProperty("jk.env.JK_KOTLIN_DIST_MIRROR", previous);
        }
    }

    private MavenSettings settings(String xml) throws Exception {
        Path file = dir.resolve("settings.xml");
        Files.writeString(file, xml);
        return MavenSettings.loadFrom(file);
    }
}
