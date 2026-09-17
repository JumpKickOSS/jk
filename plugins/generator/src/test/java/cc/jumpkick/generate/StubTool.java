// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.generate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A generator stand-in the step forks for real: writes {@code argv.txt} and {@code Hello.java}
 * into the {@code -o} directory (and an example tree under {@code generated-examples} when told
 * {@code --example-dir}), prints one located line and one log line to stderr, and exits with the
 * value after {@code --exit}.
 */
public final class StubTool {

    private StubTool() {}

    public static void main(String[] args) throws IOException {
        List<String> argv = List.of(args);
        Path out = Path.of(argv.get(argv.indexOf("-o") + 1));
        Files.writeString(out.resolve("argv.txt"), String.join("\n", argv));
        Files.writeString(out.resolve("Hello.java"), "class Hello {}");
        if (argv.contains("--example-dir")) {
            Files.createDirectories(out.resolve("generated-examples/com/example"));
            Files.writeString(out.resolve("generated-examples/com/example/Example.java"), "class Example {}");
        }
        System.err.println(argv.get(argv.indexOf("-i") + 1) + ":3:1: deprecated `foo`");
        System.err.println("generated 1 file into " + Path.of("").toAbsolutePath());
        int exit = argv.contains("--exit") ? Integer.parseInt(argv.get(argv.indexOf("--exit") + 1)) : 0;
        System.exit(exit);
    }
}
