// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import cc.jumpkick.model.ToolchainSpec;
import java.util.Locale;

/**
 * A Node version as a project writes it: a major ({@code 24}), a line ({@code 24.21}), a point
 * release ({@code 24.21.0}, a leading {@code v} allowed), {@code lts}, {@code lts/<codename>} or
 * {@code latest}. A leading {@code =} makes a point release required; every other form is a
 * suggestion, and a discovered install satisfies a suggestion of the same major.
 */
public record NodeSpec(Kind kind, String text, boolean required) {

    public enum Kind {
        MAJOR,
        LINE,
        EXACT,
        LTS,
        LTS_CODENAME,
        LATEST
    }

    /** Parse {@code raw}; refuses what names no Node release. */
    public static NodeSpec parse(String raw) {
        String s = raw == null ? "" : raw.trim();
        boolean required = s.startsWith("=");
        if (required) s = s.substring(1).trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("v") && lower.length() > 1 && Character.isDigit(lower.charAt(1))) {
            lower = lower.substring(1);
        }
        if (lower.isEmpty()) throw new IllegalArgumentException("node version is empty");
        NodeSpec spec;
        if (lower.equals("lts") || lower.equals("lts/*")) {
            spec = new NodeSpec(Kind.LTS, "lts", false);
        } else if (lower.startsWith("lts/")) {
            spec = new NodeSpec(Kind.LTS_CODENAME, lower.substring(4), false);
        } else if (lower.equals("latest") || lower.equals("current") || lower.equals("node")) {
            spec = new NodeSpec(Kind.LATEST, "latest", false);
        } else if (lower.matches("\\d+")) {
            spec = new NodeSpec(Kind.MAJOR, lower, false);
        } else if (lower.matches("\\d+\\.\\d+")) {
            spec = new NodeSpec(Kind.LINE, lower, false);
        } else if (lower.matches("\\d+\\.\\d+\\.\\d+")) {
            spec = new NodeSpec(Kind.EXACT, lower, required);
        } else {
            throw new IllegalArgumentException("node = \"" + raw + "\" names no Node release — write a major (24),"
                    + " a point release (\"24.21.0\", \"=24.21.0\" to require it), \"lts\" or \"lts/<codename>\"");
        }
        if (required && spec.kind != Kind.EXACT) {
            throw new IllegalArgumentException(
                    "node = \"=" + s + "\" pins nothing — an = version needs a point" + " release (e.g. \"=24.21.0\")");
        }
        return spec;
    }

    /**
     * The spec a {@code jk.toml} toolchain key holds: its required version with {@code =}, else the
     * suggestion; a keyword the toolchain grammar files as a vendor ({@code lts/krypton}) is read too.
     */
    public static NodeSpec of(ToolchainSpec toolchain) {
        if (!toolchain.requiredVersion().isEmpty()) return parse("=" + toolchain.requiredVersion());
        if (!toolchain.suggestedVersion().isEmpty()) return parse(toolchain.suggestedVersion());
        return parse(toolchain.suggestedVendor());
    }

    /** The major this spec names, or 0 for a keyword. */
    public int major() {
        return switch (kind) {
            case MAJOR, LINE, EXACT -> NodeRelease.majorOf(text);
            default -> 0;
        };
    }

    @Override
    public String toString() {
        return switch (kind) {
            case LTS_CODENAME -> "lts/" + text;
            case EXACT -> (required ? "=" : "") + text;
            default -> text;
        };
    }
}
