// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.mvn.MavenResolver;
import cc.jumpkick.testing.LoopbackHttp;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * With only a native {@code mirror-of = ["*"]} table in {@code ~/.jk/config.toml} — no {@code
 * [mirrors] maven}, no settings.xml — the Apache Maven distribution downloads from the mirror,
 * carrying the mirror's own credential.
 */
@Tag("integration")
class MavenDistNativeMirrorTest {

    private static final String VERSION = "3.9.9";
    private static final String ZIP_PATH =
            "/org/apache/maven/apache-maven/" + VERSION + "/apache-maven-" + VERSION + "-bin.zip";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @TempDir
    Path tmp;

    @BeforeEach
    void isolate() throws IOException {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Files.writeString(home.resolve("config.toml"), """
                [repositories.corp]
                url = "%s"
                mirror-of = ["*"]
                token = "${JK_REPO_CORP_TOKEN}"
                """.formatted(http.baseUrl()));
        System.setProperty("jk.env.JK_HOME", home.toString());
        System.setProperty("jk.env.JK_REPO_CORP_TOKEN", "corp-secret");
        System.setProperty(
                MavenSettings.SETTINGS_PROPERTY,
                tmp.resolve("absent-settings.xml").toString());
    }

    @Test
    void the_maven_distribution_downloads_from_a_native_mirror_of_everything() throws Exception {
        byte[] zip = mavenZip();
        http.served().put(ZIP_PATH, zip);
        ToolDistribution dist = MavenResolver.distributionFor(VERSION);
        assertThat(dist.downloadUri()).hasToString(http.base() + ZIP_PATH);

        ToolDistribution pinned =
                new ToolDistribution(BuildTool.MAVEN, VERSION, dist.downloadUri(), "zip", Hashing.sha256Hex(zip));
        new ToolInstaller(DistMirrors.authorized(new Http(), BuildTool.MAVEN), new ToolRegistry(tmp.resolve("tools")))
                .install(pinned, false);

        assertThat(http.requested()).contains(ZIP_PATH);
        assertThat(http.headersFor(ZIP_PATH)).hasValueSatisfying(headers -> assertThat(headers.get("Authorization"))
                .containsExactly("Bearer corp-secret"));
    }

    private static byte[] mavenZip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("apache-maven-" + VERSION + "/bin/mvn"));
            zip.write("#!/bin/sh\necho mvn\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
