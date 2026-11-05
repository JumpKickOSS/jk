// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import org.jspecify.annotations.Nullable;

/** The MCP protocol revisions whose form elicitation the flattening targets, oldest first. */
public enum McpRevision {
    /** Form elicitation of flat primitives; an enum's labels go in {@code enumNames}. */
    V2025_06_18("2025-06-18", false),
    /** Adds arrays of enums and titled options ({@code oneOf} / {@code anyOf} of {@code const} + {@code title}). */
    V2025_11_25("2025-11-25", true);

    private final String version;
    private final boolean arrays;

    McpRevision(String version, boolean arrays) {
        this.version = version;
        this.arrays = arrays;
    }

    public String version() {
        return version;
    }

    /** Whether a {@code multi} field can be one array property rather than a boolean per option. */
    public boolean arrayEnums() {
        return arrays;
    }

    /**
     * The newest revision at or before {@code protocolVersion} (a {@code YYYY-MM-DD} string), or
     * {@code null} when the client predates form elicitation.
     */
    public static @Nullable McpRevision of(String protocolVersion) {
        @Nullable McpRevision best = null;
        for (McpRevision r : values()) {
            if (r.version.compareTo(protocolVersion) <= 0) best = r;
        }
        return best;
    }
}
