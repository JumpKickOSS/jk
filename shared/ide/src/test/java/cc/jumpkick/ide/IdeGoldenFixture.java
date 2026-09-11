// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.ide;

import cc.jumpkick.wire.protocol.IdeWireModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A two-module workspace, its fake store, JDK homes and IDE config root, plus the engine wire model
 * that describes it — every path under one temp root so a generated file normalizes to a stable
 * text with {@link #WS} in place of the root. The generators read the tree (source roots, suite
 * discovery, JDK module lists), so the directories are real; the jars and classes are empty files.
 */
final class IdeGoldenFixture {

    /** Stands in for the absolute workspace root in normalized golden text. */
    static final String WS = "${WS}";

    /** The fixture as built: the wire model the engine would send and every file the fixture itself wrote. */
    record Built(Path ws, IdeWireModel wire, Path ideConfigDir, Set<Path> inputs) {}

    private IdeGoldenFixture() {}

    static Built build(Path root) throws IOException {
        Path ws = root.toAbsolutePath().normalize();
        Set<Path> inputs = new LinkedHashSet<>();

        // Workspace root and two members: one traditional layout on its own JDK, one compact with a main class.
        file(inputs, ws.resolve("jk.toml"), """
                group = "com.acme"
                name = "acme"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["core", "app"]
                """);
        Path core = ws.resolve("core");
        file(inputs, core.resolve("jk.toml"), """
                group = "com.acme"
                name = "acme-core"
                version = "1.0.0"
                java = 17
                """);
        file(inputs, core.resolve("src/main/java/com/acme/core/Core.java"), "package com.acme.core;\nclass Core {}\n");
        file(inputs, core.resolve("src/main/resources/core.properties"), "a=b\n");
        file(
                inputs,
                core.resolve("src/test/java/com/acme/core/CoreTest.java"),
                "package com.acme.core;\nclass CoreTest {}\n");
        file(inputs, core.resolve("src/test/resources/fixture.txt"), "x\n");
        file(
                inputs,
                core.resolve("src/integration/java/com/acme/core/CoreIT.java"),
                "package com.acme.core;\nclass CoreIT {}\n");

        Path app = ws.resolve("app");
        file(inputs, app.resolve("jk.toml"), """
                group = "com.acme"
                name = "acme-app"
                version = "1.0.0"
                java = 25

                [application]
                main = "com.acme.app.Main"
                """);
        file(inputs, app.resolve("src/com/acme/app/Main.java"), "package com.acme.app;\nclass Main {}\n");
        file(inputs, app.resolve("resources/app.properties"), "c=d\n");
        file(inputs, app.resolve("test/src/com/acme/app/MainTest.java"), "package com.acme.app;\nclass MainTest {}\n");
        file(inputs, app.resolve("test/resources/data.txt"), "y\n");
        file(
                inputs,
                app.resolve("guard/src/com/acme/app/HouseRules.java"),
                "package com.acme.app;\nclass HouseRules {}\n");

        // The store: real .jar names, empty bodies.
        Path store = ws.resolve("store");
        Path slf4j = store.resolve("org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar");
        Path slf4jSources = store.resolve("org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17-sources.jar");
        Path jupiter = store.resolve("org/junit/jupiter/junit-jupiter/5.13.4/junit-jupiter-5.13.4.jar");
        Path lombok = store.resolve("org/projectlombok/lombok/1.18.40/lombok-1.18.40.jar");
        for (Path jar : List.of(slf4j, slf4jSources, jupiter, lombok)) file(inputs, jar, "");

        // Two JDK homes: one advertises its modules in `release`, the other only through jmods/.
        Path jdk25 = ws.resolve("jdks/temurin-25");
        file(inputs, jdk25.resolve("release"), "JAVA_VERSION=\"25.0.3\"\nMODULES=\"java.sql java.base java.xml\"\n");
        Path jdk17 = ws.resolve("jdks/temurin-17");
        file(inputs, jdk17.resolve("release"), "JAVA_VERSION=\"17.0.16\"\n");
        file(inputs, jdk17.resolve("jmods/java.logging.jmod"), "");
        file(inputs, jdk17.resolve("jmods/java.base.jmod"), "");

        // IDE config root: two Java IDEs and one that keeps no JDK table.
        Path ideConfig = ws.resolve("ide-config");
        Files.createDirectories(ideConfig.resolve("JetBrains/IntelliJIdea2025.1/options"));
        Files.createDirectories(ideConfig.resolve("JetBrains/PyCharm2025.1/options"));
        Files.createDirectories(ideConfig.resolve("Google/AndroidStudio2025.1/options"));

        List<String> dirs = List.of(core.toString(), app.toString());
        IdeWireModel wire = new IdeWireModel(
                null,
                ws.toString(),
                "acme",
                true,
                dirs,
                List.of("acme-core", "acme-app"),
                List.of("17", "25"),
                List.of("", "com.acme.app.Main"),
                under(dirs, "target/classes"),
                under(dirs, "target/test-classes"),
                under(dirs, "target/jdt/classes/main"),
                under(dirs, "target/jdt/classes/test"),
                under(dirs, "target/generated-sources/annotations"),
                under(dirs, "target/generated-sources/annotations-test"),
                List.of(
                        "org.slf4j:slf4j-api:2.0.17",
                        "org.junit.jupiter:junit-jupiter:5.13.4",
                        "org.projectlombok:lombok:1.18.40"),
                List.of(
                        "org.slf4j_slf4j-api_2.0.17",
                        "org.junit.jupiter_junit-jupiter_5.13.4",
                        "org.projectlombok_lombok_1.18.40"),
                List.of(slf4j.toString(), jupiter.toString(), lombok.toString()),
                List.of(slf4jSources.toString(), "", ""),
                List.of("1|acme-core|" + IdeWireModel.SCOPE_COMPILE_TEST_KIND),
                List.of(
                        "0|org.slf4j:slf4j-api:2.0.17|MAIN",
                        "0|org.junit.jupiter:junit-jupiter:5.13.4|TEST",
                        "1|org.slf4j:slf4j-api:2.0.17|MAIN,TEST",
                        "1|org.junit.jupiter:junit-jupiter:5.13.4|TEST",
                        "1|org.projectlombok:lombok:1.18.40|PROVIDED,PROCESSOR"),
                List.of("1|" + lombok),
                List.of("temurin-17", "temurin-25"),
                List.of("jk-temurin-17", "jk-temurin-25"),
                List.of("17", "25"),
                List.of(jdk17.toString(), jdk25.toString()),
                List.of("17.0.16", "25.0.3"),
                "temurin-25",
                "jk-temurin-25",
                25,
                jdk25.toString(),
                "25.0.3",
                List.of("jk-temurin-25|" + jdk25 + "|25.0.3", "jk-temurin-17|" + jdk17 + "|17.0.16"));
        return new Built(ws, wire, ideConfig, inputs);
    }

    /** Every regular file under {@code ws} the fixture did not write, by forward-slash relative path, normalized. */
    static Map<String, String> outputs(Built built) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(built.ws())) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                if (built.inputs().contains(p)) continue;
                String rel = built.ws().relativize(p).toString().replace('\\', '/');
                out.put(rel, normalize(Files.readString(p, StandardCharsets.UTF_8), built.ws()));
            }
        }
        return out;
    }

    /**
     * Replace the workspace root with {@link #WS}: the absolute form, and the {@code $USER_HOME$}
     * form IntelliJ library URLs take when the root sits under the user's home.
     */
    static String normalize(String content, Path ws) {
        String abs = ws.toString().replace('\\', '/');
        String s = content.replace(abs, WS);
        String home = System.getProperty("user.home", "").replace('\\', '/');
        if (!home.isBlank() && abs.startsWith(home + "/")) {
            s = s.replace("$USER_HOME$/" + abs.substring(home.length() + 1), WS);
        }
        return s;
    }

    private static List<String> under(List<String> dirs, String rel) {
        List<String> out = new ArrayList<>(dirs.size());
        for (String d : dirs) out.add(Path.of(d).resolve(rel).toString());
        return out;
    }

    private static void file(Set<Path> inputs, Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        inputs.add(file);
    }
}
