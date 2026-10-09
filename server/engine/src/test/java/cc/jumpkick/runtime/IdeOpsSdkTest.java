// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.base.IdeOps;
import cc.jumpkick.testing.FakeJdk;
import cc.jumpkick.wire.protocol.IdeWireModel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module's SDK in the ide-model is the JDK the build compiles it with: {@code java = 17} under a
 * JDK 25 is the JDK 25 SDK at language level 17, and only a {@code jdk =} pin names another
 * install. Every SDK a module names is listed in {@code sdkEntries}.
 */
class IdeOpsSdkTest {

    @Test
    void java_release_is_a_language_level_and_jdk_pins_the_sdk(@TempDir Path tmp) throws Exception {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        FakeJdk.create(jdks.resolve("temurin-25.0.1"), "25.0.1");
        FakeJdk.create(jdks.resolve("corretto-24.0.2"), "24.0.2", "Amazon.com Inc.");
        FakeJdk.create(jdks.resolve("temurin-21.0.5"), "21.0.5");

        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws.resolve("legacy"));
        Files.createDirectories(ws.resolve("pinned"));
        Files.createDirectories(ws.resolve("older"));
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["legacy", "pinned", "older"]
                """);
        Files.writeString(ws.resolve("legacy/jk.toml"), """
                group = "com.example"
                name = "legacy"
                version = "1.0.0"
                java = 17
                """);
        Files.writeString(ws.resolve("pinned/jk.toml"), """
                group = "com.example"
                name = "pinned"
                version = "1.0.0"
                jdk = "corretto-24"
                """);

        Files.writeString(ws.resolve("older/jk.toml"), """
                group = "com.example"
                name = "older"
                version = "1.0.0"
                jdk = 21
                """);

        IdeWireModel model = IdeOps.ideModel(ws, tmp.resolve("cache"), jdks, false);
        assertThat(model.error()).isNull();

        int legacy = model.names().indexOf("legacy");
        assertThat(model.sdkNames().get(legacy)).isEqualTo("jk-temurin-25");
        assertThat(model.sdkLevels().get(legacy)).isEqualTo("17");
        assertThat(model.sdkVersions().get(legacy)).isEqualTo("25.0.1");

        int pinned = model.names().indexOf("pinned");
        assertThat(model.sdkNames().get(pinned)).isEqualTo("jk-corretto-24");
        assertThat(model.sdkLevels().get(pinned)).isEqualTo("24");

        int older = model.names().indexOf("older");
        assertThat(model.sdkNames().get(older)).isEqualTo("jk-temurin-21");
        assertThat(model.sdkLevels().get(older)).isEqualTo("21");

        assertThat(model.defSdkName()).isEqualTo("jk-temurin-25");
        assertThat(model.sdkEntries())
                .extracting(e -> e.substring(0, e.indexOf('|')))
                .containsExactlyInAnyOrder("jk-temurin-25", "jk-corretto-24", "jk-temurin-21");
        for (String home : model.sdkHomes()) assertThat(Path.of(home)).isDirectory();
    }
}
