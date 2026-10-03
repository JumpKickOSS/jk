// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import cc.jumpkick.jsonl.MiniJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One row of Node's {@code index.json}: the version without its {@code v}, the bundled npm, the LTS
 * codename ({@code null} for a Current release) and whether it is a security release.
 */
public record NodeRelease(
        String version, @Nullable String npm, @Nullable String lts, boolean security) {

    public NodeRelease {
        Objects.requireNonNull(version, "version");
        if (version.startsWith("v")) version = version.substring(1);
    }

    /** The major line ({@code 24} for {@code 24.21.0}); 0 when the version is not numeric. */
    public int major() {
        return majorOf(version);
    }

    /** A pre-release ({@code 26.0.0-rc.1}) is never selected. */
    public boolean preRelease() {
        return version.indexOf('-') >= 0;
    }

    static int majorOf(String version) {
        String v = version.startsWith("v") ? version.substring(1) : version;
        int dot = v.indexOf('.');
        try {
            return Integer.parseInt(dot < 0 ? v : v.substring(0, dot));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The rows of an {@code index.json} body, newest first as the file lists them. */
    static List<NodeRelease> parseIndex(String json) {
        Object root = MiniJson.parse(json);
        if (!(root instanceof List<?> rows)) throw new IllegalArgumentException("Node index is not a JSON array");
        List<NodeRelease> out = new ArrayList<>(rows.size());
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> m)) continue;
            String version = MiniJson.str(m, "version");
            if (version == null || version.isBlank()) continue;
            Object lts = m.get("lts");
            out.add(new NodeRelease(
                    version,
                    MiniJson.str(m, "npm"),
                    lts instanceof String codename && !codename.isBlank() ? codename : null,
                    Boolean.TRUE.equals(m.get("security"))));
        }
        return out;
    }
}
