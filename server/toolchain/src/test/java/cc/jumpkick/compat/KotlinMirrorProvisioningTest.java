// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.testing.LoopbackHttp;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** The Kotlin compiler downloads from a configured mirror, with the mirror's credential, drawing the JDK's progress. */
@DisabledOnOs(OS.WINDOWS)
class KotlinMirrorProvisioningTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final List<String> overlays = new ArrayList<>();

    @AfterEach
    void clear() {
        overlays.forEach(name -> System.clearProperty("jk.env." + name));
    }

    @Test
    void the_compiler_comes_from_the_mirror_with_its_token_and_reports_total_then_install(@TempDir Path tmp)
            throws Exception {
        String version = "9.9.9";
        byte[] zip = zip("kotlinc", "bin/kotlinc", "#!/bin/sh\nexit 0\n");
        http.served().put("/kt/v" + version + "/kotlin-compiler-" + version + ".zip", zip);
        http.served()
                .put(
                        "/kt/v" + version + "/kotlin-compiler-" + version + ".zip.sha256",
                        Hashing.sha256Hex(zip).getBytes(StandardCharsets.UTF_8));
        overlay("JK_KOTLIN_DIST_MIRROR", http.base().resolve("/kt/").toString());
        overlay("JK_REPO_127_0_0_1_" + http.base().getPort() + "_TOKEN", "t0ken");

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
        ToolProvisioning.Result result = ToolProvisioning.provision(
                KotlinResolver.distributionFor(version),
                new ToolRegistry(tmp.resolve("tools")),
                new Http(),
                new ToolProvisioning.Policy(true, false, false),
                recorder);

        assertThat(result.source()).isEqualTo(ToolProvisioning.Result.Source.DOWNLOADED);
        assertThat(result.detail()).startsWith(http.base().resolve("/kt/").toString());
        assertThat(http.headersFor("/kt/v" + version + "/kotlin-compiler-" + version + ".zip"))
                .get()
                .extracting(h -> h.get("Authorization"))
                .isEqualTo(List.of("Bearer t0ken"));
        assertThat(events.getFirst()).startsWith("download Kotlin").endsWith("/" + zip.length);
        assertThat(events).anyMatch(e -> e.startsWith("install Kotlin"));
    }

    private void overlay(String name, String value) {
        System.setProperty("jk.env." + name, value);
        overlays.add(name);
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
