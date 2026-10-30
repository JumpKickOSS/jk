// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.host.ManifestNames;
import cc.jumpkick.wire.protocol.GraphRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The graph worker's startup-cache trainer: the same {@code java -cp worker PluginMain spec} shape
 * as a real run, recording with {@code -XX:AOTCacheOutput} while it builds and queries a small
 * workspace with a file of each kind the extractors read.
 */
final class GraphTraining {

    private GraphTraining() {}

    static List<String> command(Path hostJava, String workerCp, Path aotOutput, Path scratch) throws IOException {
        Path root = scratch.resolve("workspace");
        for (Map.Entry<String, String> file : FILES.entrySet()) {
            Path p = root.resolve(file.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, file.getValue(), StandardCharsets.UTF_8);
        }
        GraphRequest request = new GraphRequest(
                root.toString(),
                "query",
                List.of("Greeter greet"),
                "bfs",
                3,
                2000,
                List.of(),
                false,
                8,
                null,
                false,
                false,
                null,
                null,
                null,
                List.of());
        Path spec = scratch.resolve("train.spec");
        Files.write(
                spec,
                GraphRunner.specWriter(root, request, List.of(), List.of(), null)
                        .lines(),
                StandardCharsets.UTF_8);
        PluginLoader.sealNetworkPolicy(spec);
        List<String> jvmFlags = new ArrayList<>();
        jvmFlags.add("-XX:AOTCacheOutput=" + aotOutput);
        jvmFlags.addAll(JvmOptions.batchFlags(1));
        return PluginLoader.command(
                hostJava, workerCp, jvmFlags, List.of(spec.toAbsolutePath().toString()));
    }

    private static final Map<String, String> FILES = Map.of(
            ManifestNames.MANIFEST,
            """
            [project]
            name = "demo"
            java = 25
            """,
            "src/main/java/demo/Greeter.java",
            """
            package demo;

            import java.util.List;

            /** Says hello. */
            public class Greeter implements Named {
                private final List<String> names;

                public Greeter(List<String> names) {
                    this.names = names;
                }

                public String greet() {
                    return "hello " + String.join(", ", names);
                }

                @Override
                public String name() {
                    return greet();
                }
            }
            """,
            "src/main/java/demo/Named.java",
            """
            package demo;

            public interface Named {
                String name();
            }
            """,
            "src/main/resources/application.yml",
            """
            greeter:
              names: [a, b]
            """,
            "README.md",
            """
            # Demo

            `Greeter` greets; see [Named](src/main/java/demo/Named.java).
            """,
            "web/app.ts",
            """
            import { greet } from "./greet";

            export class App {
              run(): string {
                return greet("web");
              }
            }
            """,
            "web/greet.ts",
            """
            export function greet(who: string): string {
              return `hello ${who}`;
            }
            """,
            "web/app.css",
            """
            @import "base.css";
            .app { color: red; }
            """);
}
