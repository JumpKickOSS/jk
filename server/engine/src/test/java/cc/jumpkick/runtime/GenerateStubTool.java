// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The generator a {@code [generate]} e2e forks: {@code <unpacked dir> <out dir>} reads
 * {@code names.txt} out of the unpacked jar and writes {@code gen/Hello.java} carrying its text.
 */
public final class GenerateStubTool {

    private GenerateStubTool() {}

    public static void main(String[] args) throws IOException {
        Path unpacked = Path.of(args[0]);
        Path out = Path.of(args[1]);
        String name = Files.readString(unpacked.resolve("names.txt")).strip();
        Path source = out.resolve("gen/Hello.java");
        Files.createDirectories(source.getParent());
        Files.writeString(
                source,
                "package gen;\n\npublic final class Hello {\n    public static final String NAME = \"" + name
                        + "\";\n\n    private Hello() {}\n}\n");
    }
}
