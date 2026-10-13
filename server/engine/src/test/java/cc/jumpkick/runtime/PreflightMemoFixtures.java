// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

/** Projects and workspaces on disk for the preflight memo tests. */
final class PreflightMemoFixtures {

    private PreflightMemoFixtures() {}

    static void writeProject(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("target"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                jdk = 25
                java = 25
                """);
        Path src = dir.resolve("src/main/java");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "class App {}\n");
        Files.writeString(dir.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "test"
                resolution-algorithm = "pubgrub-v1"
                """);
    }

    static void writeWorkspace(Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.1.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["a", "b"]
                """);
        Files.createDirectories(dir.resolve("target"));
        for (String m : new String[] {"a", "b"}) {
            Path md = dir.resolve(m);
            // The real layout: member outputs live under <workspace>/target/<rel>/, and
            // <member>/target is never created — the fixture must match production.
            Files.createDirectories(dir.resolve("target").resolve(m));
            Files.createDirectories(md.resolve("src/main/java"));
            Files.writeString(md.resolve("jk.toml"), """
                    group = "t"
                    name = "%s"
                    version = "0.1.0"
                    jdk = 25
                    java = 25
                    """.formatted(m));
            Files.writeString(md.resolve("src/main/java/M.java"), "class M {}\n");
            Files.writeString(md.resolve("jk-lock.toml"), """
                    version = 1
                    generated-by = "test"
                    resolution-algorithm = "pubgrub-v1"
                    """);
        }
    }
}
