// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.mcp.McpRevision;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Which {@code elicitation/create} modes a session's client declared at {@code initialize}, as far
 * as the negotiated revision carries them. {@code "elicitation": {}} is form mode alone; URL mode
 * needs {@code "url": {}} and revision 2025-11-25.
 */
public record McpElicitModes(boolean form, boolean url) {

    public static final McpElicitModes NONE = new McpElicitModes(false, false);

    /** Neither mode: the client cannot be asked anything. */
    public boolean none() {
        return !form && !url;
    }

    /** The modes in {@code initialize}'s {@code params.capabilities}, under {@code revision}. */
    public static McpElicitModes declared(@Nullable Object capabilities, McpRevision revision) {
        if (!revision.formElicitation()) return NONE;
        if (!(capabilities instanceof Map<?, ?> caps) || !(caps.get("elicitation") instanceof Map<?, ?> e)) {
            return NONE;
        }
        if (!revision.urlElicitation()) return new McpElicitModes(true, false);
        boolean form = e.containsKey("form");
        boolean url = e.containsKey("url");
        return form || url ? new McpElicitModes(form, url) : new McpElicitModes(true, false);
    }
}
