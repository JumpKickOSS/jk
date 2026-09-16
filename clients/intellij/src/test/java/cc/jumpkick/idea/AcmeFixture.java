// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A two-module workspace on disk plus the {@code ide-model} JSON the CLI prints for it, captured
 * from {@code jk ide --print-model} with its absolute paths templated: {@code core} is SIMPLE
 * layout with an {@code integration} suite, {@code app} is TRADITIONAL with Lombok as a
 * processor and a tests-kind edge onto {@code core}.
 */
final class AcmeFixture {

    final Path ws;
    final Path jdks;
    final Path m2;
    final String json;

    private AcmeFixture(Path ws, Path jdks, Path m2, String json) {
        this.ws = ws;
        this.jdks = jdks;
        this.m2 = m2;
        this.json = json;
    }

    static AcmeFixture create(Path root) throws IOException {
        Path ws = root.resolve("acme");
        Path jdks = root.resolve("jdks");
        Path m2 = root.resolve("m2");
        Files.writeString(mk(ws).resolve("jk.toml"), "[workspace]\nmembers = [\"core\", \"app\"]\n");
        Files.writeString(mk(ws.resolve("core")).resolve("jk.toml"), "name = \"acme-core\"\n");
        touch(ws.resolve("core/src/com/acme/core/Core.java"));
        touch(ws.resolve("core/resources/core.properties"));
        touch(ws.resolve("core/test/src/com/acme/core/CoreTest.java"));
        touch(ws.resolve("core/test/resources/fixture.txt"));
        touch(ws.resolve("core/integration/src/com/acme/core/CoreIT.java"));
        Files.writeString(mk(ws.resolve("app")).resolve("jk.toml"), "name = \"acme-app\"\n");
        touch(ws.resolve("app/src/main/java/com/acme/app/Main.java"));
        touch(ws.resolve("app/src/main/resources/app.properties"));
        touch(ws.resolve("app/src/test/java/com/acme/app/MainTest.java"));
        touch(ws.resolve("app/src/guard/java/com/acme/app/MainGuardTest.java"));
        mk(jdks.resolve("temurin-17"));
        mk(jdks.resolve("temurin-25"));
        for (String jar : new String[] {
            "org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar",
            "org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17-sources.jar",
            "org/junit/jupiter/junit-jupiter/5.13.4/junit-jupiter-5.13.4.jar",
            "org/projectlombok/lombok/1.18.40/lombok-1.18.40.jar"
        }) {
            touch(m2.resolve(jar));
        }
        String json;
        try (InputStream in = AcmeFixture.class.getResourceAsStream("/acme-ide-model.json")) {
            if (in == null) throw new IOException("acme-ide-model.json missing from the test resources");
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        json = json.replace("${WS}", slashes(ws))
                .replace("${JDK}", slashes(jdks))
                .replace("${M2}", slashes(m2));
        return new AcmeFixture(ws, jdks, m2, json);
    }

    JkWireModel model() {
        return JkWireModel.parse(json);
    }

    private static String slashes(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static Path mk(Path dir) throws IOException {
        return Files.createDirectories(dir);
    }

    private static void touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "");
    }
}
