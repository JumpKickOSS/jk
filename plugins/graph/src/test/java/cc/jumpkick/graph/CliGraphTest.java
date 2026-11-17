// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.Relations;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compiled picocli commands define their program's command and flag anchors: a top command names
 * the program, its subcommands the commands under it, an option on a field, a setter or a superclass
 * a flag, the standard help options when it mixes them in; a script passing a flag links to it, and
 * a command with no name defines nothing.
 */
class CliGraphTest {

    private static Map<String, String> files() {
        Map<String, String> f = new TreeMap<>();
        f.put("src/main/java/picocli/CommandLine.java", """
                package picocli;

                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                public class CommandLine {
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface Command {
                        String name() default "<main class>";
                        Class<?>[] subcommands() default {};
                        boolean mixinStandardHelpOptions() default false;
                    }

                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface Option {
                        String[] names();
                    }
                }
                """);
        f.put("src/main/java/com/acme/ShopCli.java", """
                package com.acme;

                import picocli.CommandLine.Command;
                import picocli.CommandLine.Option;

                @Command(name = "shop", subcommands = {ShopCli.Serve.class}, mixinStandardHelpOptions = true)
                public class ShopCli {
                    @Option(names = {"-c", "--config-file"})
                    String config;

                    static class Common {
                        @Option(names = "--log-level")
                        String level;
                    }

                    @Command(name = "serve")
                    static class Serve extends Common {
                        int port;

                        @Option(names = {"-p", "--listen-port"})
                        void setPort(int port) {
                            this.port = port;
                        }
                    }

                    @Command
                    static class Unnamed {
                        @Option(names = "--orphan")
                        boolean orphan;
                    }
                }
                """);
        f.put("scripts/start.sh", """
                #!/bin/sh
                shop serve --listen-port 8080 --log-level debug
                """);
        return f;
    }

    @Test
    void compiledCommandsDefineTheirFlagsAndAScriptPassingOneUsesIt(@TempDir Path dir) throws IOException {
        CodeGraph g = AnchorGraphTest.graph(dir, files());
        assertThat(g.nodes().stream()
                        .map(n -> n.id())
                        .filter(id -> id.startsWith("cli:"))
                        .sorted())
                .containsExactly(
                        "cli:shop",
                        "cli:shop --config-file",
                        "cli:shop --help",
                        "cli:shop --version",
                        "cli:shop -V",
                        "cli:shop -c",
                        "cli:shop -h",
                        "cli:shop serve",
                        "cli:shop serve --listen-port",
                        "cli:shop serve --log-level",
                        "cli:shop serve -p");
        assertThat(g.incident("cli:shop serve --listen-port"))
                .extracting(GraphEdge::sourceFile, GraphEdge::relation, GraphEdge::confidence, GraphEdge::context)
                .containsExactlyInAnyOrder(
                        tuple(
                                "src/main/java/com/acme/ShopCli.java",
                                Relations.DEFINES,
                                Confidence.DECLARED,
                                "method setPort"),
                        tuple("scripts/start.sh", Relations.USES, Confidence.INFERRED, null));
        assertThat(g.incident("cli:shop serve --log-level"))
                .as("an option a superclass declares")
                .extracting(GraphEdge::relation, GraphEdge::context)
                .containsExactlyInAnyOrder(tuple(Relations.DEFINES, "field level"), tuple(Relations.USES, null));
    }
}
