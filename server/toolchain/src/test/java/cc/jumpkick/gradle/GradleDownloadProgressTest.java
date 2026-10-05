// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ToolProgress;
import cc.jumpkick.compat.ToolProvisioning;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.testing.LoopbackHttp;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** The Gradle distribution an import provisions draws its download as a JDK download does. */
@DisabledOnOs(OS.WINDOWS)
class GradleDownloadProgressTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @Test
    void the_wrapper_s_distribution_download_reports_its_total_and_install(@TempDir Path tmp) throws Exception {
        byte[] zip = zip("gradle-9.9", "bin/gradle", "#!/bin/sh\nexit 3\n");
        http.served().put("/gradle-9.9-bin.zip", zip);
        Path wrapper = Files.createDirectories(tmp.resolve("build/gradle/wrapper"));
        Files.writeString(
                wrapper.resolve("gradle-wrapper.properties"),
                "distributionUrl=" + http.base().resolve("/gradle-9.9-bin.zip") + "\n" + "distributionSha256Sum="
                        + Hashing.sha256Hex(zip) + "\n");
        Files.writeString(tmp.resolve("build/settings.gradle"), "");

        List<String> events = new CopyOnWriteArrayList<>();
        ToolProgress recorder = new ToolProgress() {
            @Override
            public void downloading(String name, long readBytes, long totalBytes) {
                events.add("download " + name + " " + readBytes + "/" + totalBytes);
            }

            @Override
            public void installing(String name) {
                events.add("install " + name);
            }
        };
        GradleModelQuery query = GradleModelQuery.provisioning(
                tmp.resolve("tools"), new Http(), ToolProvisioning.Policy.DEFAULT, tmp.resolve("scratch"), recorder);

        try {
            query.read(tmp.resolve("build"), List.of("java"), note -> {});
        } catch (IOException | RuntimeException expected) {
            // The stub's gradle exits 3: the read fails after the download this test is about.
        }

        String total = "/" + zip.length;
        assertThat(events).isNotEmpty();
        assertThat(events.getFirst()).startsWith("download Gradle").endsWith(total);
        assertThat(events).anyMatch(e -> e.startsWith("install Gradle"));
    }

    private static byte[] zip(String top, String name, String body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(top + "/" + name));
            zip.write(body.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
