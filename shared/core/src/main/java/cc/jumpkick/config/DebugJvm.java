// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A request to start the JVM under test or run with a JDWP listener: the {@code --debug-jvm} flag
 * of {@code jk test} and {@code jk run}, and the {@code data.debug} field of a BSP test or run.
 *
 * <p>The spec grammar is {@code [[host:]port][,suspend=y|n]}: an empty spec is {@link #DEFAULT}
 * ({@code localhost:5005}, suspended — what a stock IDE remote configuration attaches to);
 * {@code 0} asks the client to pick a free port before the launch; {@code *:port} listens on
 * every interface. {@link #spelling()} is the canonical form and parses back to an equal value, so
 * it is what rides the wire.
 *
 * <p>Only the one JVM the user asked to debug ever sees {@link #agentArg()}: the engine, compiler
 * workers, discovery and pull-mode test workers are started without it.
 */
public record DebugJvm(String host, int port, boolean suspend) {

    public static final int DEFAULT_PORT = 5005;

    public static final String DEFAULT_HOST = "localhost";

    public static final DebugJvm DEFAULT = new DebugJvm(DEFAULT_HOST, DEFAULT_PORT, true);

    public DebugJvm {
        host = host == null || host.isBlank() ? DEFAULT_HOST : host.trim();
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("debug port must be 0..65535, got " + port);
        }
    }

    /** Parse a spec; {@code null} or blank means {@link #DEFAULT}. */
    public static DebugJvm parse(@Nullable String spec) {
        if (spec == null || spec.isBlank()) return DEFAULT;
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        boolean suspend = true;
        for (String rawPart : spec.split(",")) {
            String part = rawPart.trim();
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            if (eq >= 0) {
                String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String value = part.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
                if (!"suspend".equals(key)) {
                    throw new IllegalArgumentException(
                            "unknown --debug-jvm setting '" + key + "' (expected [[host:]port][,suspend=y|n])");
                }
                suspend = switch (value) {
                    case "y", "yes", "true" -> true;
                    case "n", "no", "false" -> false;
                    default -> throw new IllegalArgumentException("suspend must be y or n, got '" + value + "'");
                };
                continue;
            }
            int colon = part.lastIndexOf(':');
            String portText = colon >= 0 ? part.substring(colon + 1).trim() : part;
            if (colon >= 0) host = part.substring(0, colon).trim();
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "debug port must be a number, got '" + portText + "' (expected [[host:]port][,suspend=y|n])");
            }
        }
        return new DebugJvm(host, port, suspend);
    }

    /** {@link #parse}, or {@code null} for a {@code null}/blank spec — the wire's "no debug" reading. */
    public static @Nullable DebugJvm parseOrNull(@Nullable String spec) {
        return spec == null || spec.isBlank() ? null : parse(spec);
    }

    /** Canonical {@code host:port,suspend=y|n}; {@link #parse} of it yields an equal value. */
    public String spelling() {
        return address() + ",suspend=" + (suspend ? "y" : "n");
    }

    /** {@code host:port} — what a debugger connects to. */
    public String address() {
        return host + ":" + port;
    }

    /** True until a client has replaced the {@code 0} with a port it bound. */
    public boolean portChosenByClient() {
        return port == 0;
    }

    public DebugJvm withPort(int newPort) {
        return new DebugJvm(host, newPort, suspend);
    }

    /** The one JVM flag this stands for. */
    public String agentArg() {
        return "-agentlib:jdwp=transport=dt_socket,server=y,suspend=" + (suspend ? "y" : "n") + ",address=" + address();
    }
}
