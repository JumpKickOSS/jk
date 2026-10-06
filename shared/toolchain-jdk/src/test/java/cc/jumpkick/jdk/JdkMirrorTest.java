// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.DownloadOrigin;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Os;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.testing.LoopbackHttp;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** The JDK feed and the vendor archives it names come from a configured mirror, under each host's prefix. */
class JdkMirrorTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final List<String> overlays = new ArrayList<>();

    @AfterEach
    void clear() {
        overlays.forEach(name -> System.clearProperty("jk.env." + name));
    }

    @Test
    void without_a_mirror_every_url_is_used_as_the_feed_names_it() {
        JdkMirror none = JdkMirror.of(name -> null, Map.of(), MavenSettings.empty());
        URI archive = URI.create("https://github.com/adoptium/temurin25-binaries/releases/download/jdk.tar.gz");

        assertThat(none.map(archive)).isEqualTo(archive);
    }

    @Test
    void a_mirror_serves_each_host_under_its_own_prefix_keeping_port_and_query() {
        JdkMirror mirror = JdkMirror.of(name -> null, Map.of("jdk", "https://nexus.corp/jdk"), MavenSettings.empty());

        assertThat(mirror.map(URI.create(JdkCatalogClient.DEFAULT_FEED_URL)))
                .hasToString("https://nexus.corp/jdk/download.jetbrains.com/jdk/feed/v1/jdks.json");
        assertThat(mirror.map(URI.create("https://cdn.azul.com:8443/zulu/bin/zulu25.tar.gz?x=1")))
                .hasToString("https://nexus.corp/jdk/cdn.azul.com:8443/zulu/bin/zulu25.tar.gz?x=1");
        assertThat(origin(mirror).credentialId()).isEqualTo("nexus.corp");
    }

    @Test
    void the_environment_wins_over_the_table_and_a_settings_mirror_naming_jdk_comes_last(@TempDir Path tmp)
            throws IOException {
        Path settingsXml = Files.writeString(tmp.resolve("settings.xml"), """
                <settings>
                  <mirrors>
                    <mirror><id>corp-jdk</id><mirrorOf>jdk</mirrorOf><url>https://artifacts.corp/jdk/</url></mirror>
                  </mirrors>
                </settings>
                """);
        MavenSettings settings = MavenSettings.loadFrom(settingsXml);

        assertThat(origin(JdkMirror.of(name -> null, Map.of(), settings))).satisfies(o -> {
            assertThat(o.url()).hasToString("https://artifacts.corp/jdk/");
            assertThat(o.credentialId()).isEqualTo("corp-jdk");
        });
        assertThat(origin(JdkMirror.of(name -> null, Map.of("jdk", "https://table.corp/jdk/"), settings))
                        .url())
                .hasToString("https://table.corp/jdk/");
        assertThat(origin(JdkMirror.of(
                                name -> name.equals(JdkMirror.ENV) ? "https://env.corp/jdk" : null,
                                Map.of("jdk", "https://table.corp/jdk/"),
                                settings))
                        .url())
                .hasToString("https://env.corp/jdk/");
    }

    @Test
    void an_install_fetches_feed_and_archive_from_the_mirror_with_its_token_and_reports_its_download(@TempDir Path tmp)
            throws Exception {
        byte[] archive = zip("jdk-25.0.1+8", Map.of("bin/java", "fake", "release", "JAVA_VERSION=25.0.1\n"));
        String vendorPath = "/adoptium/temurin25-binaries/releases/download/OpenJDK25U-jdk.zip";
        http.served().put("/mirror/vendor.example" + vendorPath, archive);
        http.served()
                .put(
                        "/mirror/download.jetbrains.com/jdk/feed/v1/jdks.json",
                        feed("https://vendor.example" + vendorPath, archive).getBytes(StandardCharsets.UTF_8));
        overlay("JK_STORE_DIR", tmp.resolve("store").toString());
        overlay("JK_STATE_DIR", tmp.resolve("state").toString());
        overlay(JdkMirror.ENV, http.base().resolve("/mirror/").toString());
        overlay("JK_REPO_127_0_0_1_" + http.base().getPort() + "_TOKEN", "t0ken");

        List<String> events = new CopyOnWriteArrayList<>();
        JdkInstallListener recorder = new JdkInstallListener() {
            @Override
            public void onDownloadStart(String label, long totalBytes) {
                events.add("start " + label + " " + totalBytes);
            }

            @Override
            public void onDownloadProgress(long readBytes, long totalBytes) {
                events.add("progress " + readBytes + "/" + totalBytes);
            }

            @Override
            public void onInstalled(InstalledJdk jdk) {
                events.add("installed");
            }
        };
        InstalledJdk installed = new JdkService()
                .install(
                        "temurin-25",
                        new JdkRegistry(tmp.resolve("jdks")),
                        true,
                        null,
                        null,
                        HostPlatform.currentOs(),
                        HostPlatform.currentArch(),
                        recorder);

        assertThat(installed.home().resolve("release")).exists();
        for (String path : List.of(
                "/mirror/download.jetbrains.com/jdk/feed/v1/jdks.json", "/mirror/vendor.example" + vendorPath)) {
            assertThat(http.headersFor(path))
                    .as(path)
                    .get()
                    .extracting(h -> h.get("Authorization"))
                    .isEqualTo(List.of("Bearer t0ken"));
        }
        assertThat(events.getFirst()).isEqualTo("start Temurin 25 " + archive.length);
        assertThat(events).contains("progress " + archive.length + "/" + archive.length);
        assertThat(events.getLast()).isEqualTo("installed");
    }

    private static DownloadOrigin origin(JdkMirror mirror) {
        return Objects.requireNonNull(mirror.origin(), "mirror origin");
    }

    private void overlay(String name, String value) {
        System.setProperty("jk.env." + name, value);
        overlays.add(name);
    }

    /** A one-JDK JetBrains feed for this host's platform naming {@code url}, one key per line as the feed is. */
    private static String feed(String url, byte[] archive) {
        return """
                {
                  "jdks": [
                    {
                      "vendor": "Eclipse",
                      "product": "Temurin",
                      "default": true,
                      "jdk_version_major": 25,
                      "jdk_version": "25.0.1",
                      "suggested_sdk_name": "temurin-25",
                      "shared_index_aliases": ["temurin-25.0.1", "temurin-25", "25.0.1", "25"],
                      "packages": [
                        {
                          "os": "%s",
                          "arch": "%s",
                          "version": "25.0.1",
                          "url": "%s",
                          "package_type": "zip",
                          "package_to_java_home_prefix": "",
                          "install_folder_name": "temurin-25.0.1",
                          "archive_size": %d,
                          "sha256": "%s"
                        }
                      ]
                    }
                  ]
                }
                """.formatted(
                HostPlatform.currentOs(), HostPlatform.currentArch(), url, archive.length, Hashing.sha256Hex(archive));
    }

    private static byte[] zip(String top, Map<String, String> files) throws IOException {
        Map<String, String> all = new LinkedHashMap<>(files);
        if (Os.isWindows()) {
            all.put("bin/java.exe", "fake");
            all.put("bin/javac.exe", "fake");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var e : all.entrySet()) {
                zip.putNextEntry(new ZipEntry(top + "/" + e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
