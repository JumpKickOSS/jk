// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The client running on a JVM from its fat jar — the install shape for a host with no native
 * client (any OS and architecture a JDK 25 runs on). The launcher the installer writes ({@code
 * bin/jk}, {@code bin/jk.bat}) names the jar it runs in {@link #JAR_PROPERTY}; a client that sees
 * the property knows it is that install, and where its own bytes live. A JVM started any other way
 * — a test, {@code jk run} over the published closure, the {@code jk-jvm} launcher of a checkout —
 * carries no such claim and is not treated as an installed release.
 */
public final class JvmClient {

    /** {@code -Djk.client.jar=<path>}: the fat jar this JVM client was launched from. */
    public static final String JAR_PROPERTY = "jk.client.jar";

    private JvmClient() {}

    /** The fat jar the launcher named, or empty when this is not a launched JVM client. */
    public static Optional<Path> jar() {
        String value = System.getProperty(JAR_PROPERTY);
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(Path.of(value));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    /** True when this process is the installed JVM client (see {@link #jar()}). */
    public static boolean installed() {
        return jar().isPresent();
    }
}
