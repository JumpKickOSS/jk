// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The generator a {@code [generate] secret-env} e2e forks: {@code <out dir> <variable>} fails
 * unless the variable it names starts with {@code pw-} in its environment, then writes {@code
 * gen/Hello.java}, which holds no part of the password.
 */
public final class GenerateSecretStubTool {

    private GenerateSecretStubTool() {}

    public static void main(String[] args) throws IOException {
        String password = System.getenv(args[1]);
        if (password == null || !password.startsWith("pw-")) {
            System.err.println(args[1] + " is not set");
            System.exit(2);
        }
        Path source = Path.of(args[0]).resolve("gen/Hello.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package gen;\n\npublic final class Hello {\n    private Hello() {}\n}\n");
    }
}
