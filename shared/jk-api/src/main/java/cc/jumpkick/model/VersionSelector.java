// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Objects;

/**
 * Version selector grammar, shared by every place {@code jk.toml} names a version.
 *
 * <table>
 *   <caption>Selector forms</caption>
 *   <tr><th>Written</th><th>Means</th></tr>
 *   <tr><td>{@code 1.2.3}</td><td>{@link Exact} {@code 1.2.3}</td></tr>
 *   <tr><td>{@code =1.2.3}</td><td>{@link Exact} {@code 1.2.3}; writers emit the bare form</td></tr>
 *   <tr><td>{@code ^1.2.3}</td><td>{@link Caret}</td></tr>
 *   <tr><td>{@code ~1.2.3}</td><td>{@link Tilde}</td></tr>
 *   <tr><td>{@code >=1.2,<2}</td><td>{@link Range}</td></tr>
 *   <tr><td>{@code latest}</td><td>{@link Latest}: newest stable at the next resolve</td></tr>
 *   <tr><td>{@code snapshot}</td><td>{@link Snapshot}: newest advertised, pre-releases included</td></tr>
 * </table>
 *
 * <p>A bare version is a pin. Floating is always spelled out with a decoration or a keyword.
 */
public sealed interface VersionSelector {

    /** The text the user wrote, preserved for round-tripping and diagnostics. */
    String raw();

    record Caret(String raw, String version) implements VersionSelector {}

    record Exact(String raw, String version) implements VersionSelector {}

    record Tilde(String raw, String version) implements VersionSelector {}

    record Range(String raw) implements VersionSelector {}

    record Latest(String raw) implements VersionSelector {}

    /**
     * {@code snapshot}: the newest advertised version, pre-releases included. Every other floating
     * selector resolves to stable releases only.
     */
    record Snapshot(String raw) implements VersionSelector {}

    static VersionSelector parse(String spec) {
        Objects.requireNonNull(spec, "spec");
        String trimmed = spec.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("version selector must not be blank");
        }
        if ("latest".equalsIgnoreCase(trimmed)) {
            return new Latest(spec);
        }
        if ("snapshot".equalsIgnoreCase(trimmed)) {
            return new Snapshot(spec);
        }
        if (trimmed.startsWith("^")) {
            String inner = trimmed.substring(1).trim();
            if ("latest".equalsIgnoreCase(inner)) return new Latest(spec);
            if ("snapshot".equalsIgnoreCase(inner)) return new Snapshot(spec);
            return new Caret(spec, inner);
        }
        if (trimmed.startsWith("~")) {
            String inner = trimmed.substring(1).trim();
            if ("latest".equalsIgnoreCase(inner)) return new Latest(spec);
            if ("snapshot".equalsIgnoreCase(inner)) return new Snapshot(spec);
            return new Tilde(spec, inner);
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && !trimmed.contains(",")) {
            String inner = trimmed.substring(1, trimmed.length() - 1).trim();
            if (!inner.isEmpty()) return new Exact(spec, inner);
        }
        if (trimmed.startsWith(">")
                || trimmed.startsWith("<")
                || trimmed.startsWith("[")
                || trimmed.startsWith("(")
                || trimmed.contains(",")) {
            return new Range(spec);
        }
        if (trimmed.startsWith("=")) {
            return new Exact(spec, trimmed.substring(1).trim());
        }
        return new Exact(spec, trimmed);
    }
}
